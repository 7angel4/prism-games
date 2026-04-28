package learning;

import explicit.*;
import explicit.rewards.CSGRewards;
import explicit.rewards.MDPRewardsSimple;
import parser.ast.*;
import prism.*;
import strat.*;
import learning.PACHelper.SolveOutcome;

import java.util.*;
import java.util.stream.DoubleStream;


public class PACLearner {

    public static final double NASH_STOP_THRESH = 4.0;
    public static final double ZERO_SUM_STOP_THRESH = 2.0;
    private static final String DEFAULT_SMT_SOLVER = "Yices";
    private static final double TRANS_PROB_LB = 1e-5;

    public static final class PacResult {
        public final boolean noExactNE;
        public final int episodes;
        public final double deltaT;
        public final SolveOutcome robustSol;
        public final SolveOutcome pointSol;

        public PacResult(boolean noExactNE,
                         int episodes,
                         double deltaT,
                         SolveOutcome robustSol,
                         SolveOutcome pointSol
                         ) {
            this.noExactNE = noExactNE;
            this.episodes = episodes;
            this.deltaT = deltaT;
            this.robustSol = robustSol;
            this.pointSol = pointSol;
        }
    }

    private final Prism prism;

    private long[][] slotCounts;
    private long[][][] transitionCounts; // indexed by [s][c], then maps successor state index to count; 3rd dimension might be sparse
    private boolean[][] known;
    private int numUnknownSlots;
    private int prevNumUnknownSlots;
    private int totalNumSlots; // for easier look up

    private CSGSimple<Double> trueGame;
    private L1CSGSimple<Double> empiricalGame;
    private L1MDPSimple<Double> explorationRMDP;
    private long nMin;
    private double maxRadius = L1CSGSimple.INIT_RADIUS;
    private MDPRewardsSimple<Double> explorationRewards;
    private UCSGModelChecker robustMC;
    private CSGModelChecker pointMC;
    private Strategy<Double> explorationStrat;

    private double trueValue = Double.NaN;

    private int episode;
    private final PACHelper helper = new PACHelper();
    private Logger logger;
    private double totalRadius = 0.0;

    /**
     * The effective horizon
     * For finite-horizon properties, this is the property's upper bound.
     * For unbounded properties, this is the fallback rollout cap.
     */
    private int effHorizon;

    public PACLearner(Prism prism, int seed) throws PrismException {
        this(prism, seed, true);
    }

    public PACLearner(Prism prism, int seed, boolean genStrat) throws PrismException {
        this.prism = prism;
        this.prism.setSimulatorSeed(seed);
        this.prism.setGenStrat(genStrat);
    }

    public PacResult runPacLoop(Experiment.PacRunSpec spec, String modelFilePath, int propertyIndex) throws PrismException {
        helper.spec = spec;
        logger = new Logger(modelFilePath, propertyIndex);
        return runPacLoop(
                spec.trueGame,
                spec.propertiesFile,
                spec.property,
                spec.epsilon,
                spec.confidence,
                spec.rMax,
                spec.horizon,
                spec.solver,
                spec.zeroSum,
                spec.finiteHorizon
        );
    }

    private PacResult runPacLoop(
            CSGSimple<Double> trueGame,
            PropertiesFile propertiesFile,
            Property property,
            double eps,
            double confidence,
            double rMax,
            int horizon,
            String solver,
            boolean zeroSum,
            boolean finiteHorizon
    ) throws PrismException {

        final double deltaContain = confidence / 2.0;
        final double deltaCov = confidence / 2.0;
        episode = 1;
        this.trueGame = trueGame;

        initialiseRun(solver, propertiesFile, property, zeroSum);

        if (!finiteHorizon) {
            double pT = computeStopProb();
            System.out.println("Stopping probability: " + pT);
            this.effHorizon = (int) Math.ceil(trueGame.getNumStates() / pT);
            System.out.println("Effective horizon: " + this.effHorizon);
        } else {
            this.effHorizon = horizon;
        }

        double pReach = computeMinReachProb();
        System.out.println("Lower bound on reachability probability: " + pReach);

        double stopThreshFactor = zeroSum ? ZERO_SUM_STOP_THRESH : NASH_STOP_THRESH;
        computeNmin(deltaContain, rMax, eps, stopThreshFactor);
        double stopThresh = eps / stopThreshFactor;
        double deltaT;

        while (true) {
            deltaT = computeDeltaT(rMax, effHorizon);

            if (deltaT <= stopThresh || numUnknownSlots == 0) {
                logger.close(); // ===== NEW =====
                SolveOutcome robustSol = robustSolveL1CSG(property);
                SolveOutcome pointSol = solvePointModel(property);

                if (!robustSol.foundRNE()) {
                    if (zeroSum) throw new PrismException("No NE found but zero-sum property should always have an NE");
                    return new PacResult(true, episode, deltaT, robustSol, pointSol);
                } else {
                    return new PacResult(false, episode, deltaT, robustSol, pointSol);
                }
            }

            if (episode == 1) {
                solveExplorationRMDP();
            } else if (prevNumUnknownSlots != numUnknownSlots) {
                System.out.println("Episode " + episode + ": Resolving exploration RMDP");
                solveExplorationRMDP();
                prevNumUnknownSlots = numUnknownSlots;
            }

            int numSamples = computeNumSamples(deltaCov, episode, pReach);

            for (int i = 0; i < numSamples; i++)
                helper.sampleTrajectory(effHorizon, this::updateCount);

            update(deltaContain);
            logger.logEpisode(episode, deltaT, numSamples, maxRadius, totalRadius / totalNumSlots, numUnknownSlots, (double) numUnknownSlots / totalNumSlots);
            episode++;
        }
    }

    private int computeNumSamples(double deltaCov, int episode, double pReach) {
        double deltaEpisode = deltaCov / (episode * (episode + 1.0));
        return (int) Math.ceil(- Math.log(deltaEpisode) * Math.min(numUnknownSlots, 1.0 / pReach));
//        return (int) Math.ceil(- Math.log(deltaEpisode) * Math.max(1, numUnknownSlots));
    }


    private void updateCount(int s, int c, int succ) {
        slotCounts[s][c] += 1L;
        long[] counts = transitionCounts[s][c];
        counts[succ] += 1L;
    }

    private void computeNmin(double deltaContain, double rMax, double eps, double stopThreshFactor) {
        int numStates = trueGame.getNumStates();
        int numChoices = trueGame.getActions().size();

        double c = -stopThreshFactor * stopThreshFactor * rMax * rMax * Math.pow(effHorizon, 4.0) / (eps * eps);
        double inner = Math.sqrt(deltaContain / (2.0 * (Math.pow(2, numStates) - 2.0) * numStates * numChoices)) / c;
        double n = c * helper.lambertWm1(inner);
        nMin = Math.max(1L, Math.round(n));
    }


    private void initialiseRun(String solver, PropertiesFile propertiesFile, Property property, boolean zeroSum) throws PrismException {
        int numStates = trueGame.getNumStates();
        List<List<Distribution<Double>>> trans = new ArrayList<>();

        slotCounts = new long[numStates][];
        transitionCounts = new long[numStates][][];
        known = new boolean[numStates][];
        numUnknownSlots = 0;

        for (int s = 0; s < numStates; s++) {
            int numChoices = trueGame.getNumChoices(s);
            known[s] = new boolean[numChoices];
            numUnknownSlots += numChoices;
            trans.add(new ArrayList<>(numChoices));
            slotCounts[s] = new long[numChoices];
            transitionCounts[s] = new long[numChoices][];

            for (int c = 0; c < numChoices; c++) {
                transitionCounts[s][c] = new long[numStates];

                Iterator<Integer> successors = trueGame.getSuccessorsIterator(s, c);
                List<Integer> succs = new ArrayList<>();
                while (successors.hasNext()) {
                    int succ = successors.next();
                    succs.add(succ);
                }

                Distribution<Double> uniform = new Distribution<>(Evaluator.forDouble());
                if (!succs.isEmpty()) {
                    double p = 1.0 / succs.size();
                    for (int succ : succs) {
                        uniform.add(succ, p);
                    }
                }
                trans.get(s).add(uniform);
            }
        }

        prevNumUnknownSlots = numUnknownSlots;
        totalNumSlots = numUnknownSlots;
        totalRadius = totalNumSlots * L1CSGSimple.INIT_RADIUS;

        empiricalGame = new L1CSGSimple<>(trueGame, trans);
        explorationRMDP = new L1MDPSimple<>(empiricalGame);
        explorationRewards = new MDPRewardsSimple<>(explorationRMDP.getNumStates());
        String chosenSolver = (solver == null || solver.isBlank()) ? DEFAULT_SMT_SOLVER : solver;
        prism.getSettings().set(PrismSettings.PRISM_SMT_SOLVER, chosenSolver);

        helper.setStateToIndex(trueGame);
        initialiseMCs(propertiesFile, property, zeroSum);
        prism.loadModelIntoSimulator();
        helper.sim = prism.getSimulator();
    }

    private void updateExplorationReward(int s, int c) {
        double rew = 0.0;
        if (!known[s][c]) {
            double r = empiricalGame.getRadius(s, c);
            rew = r * r; // quadratic in radius to incentivise reducing uncertainty
        }
        explorationRewards.setTransitionReward(s, c, rew);
    }

    private void updateKnown(int s, int c) {
        boolean isKnown = slotCounts[s][c] >= nMin;
        if (isKnown && !known[s][c]) {
            numUnknownSlots -= 1;
            System.out.println("Slot (" + s + "," + c + ") is now known. Remaining unknown slots: " + numUnknownSlots);
        }
        known[s][c] = isKnown;
    }

    // update L1 transitions in empiricalGame and explorationRMDP, exploration rewards, and known
    private void update(double deltaContain) {
        maxRadius = 0.0;
        int numStates = empiricalGame.getNumStates();

        for (int s = 0; s < numStates; s++) {
            for (int c = 0; c < empiricalGame.getNumChoices(s); c++) {
                long saCount = slotCounts[s][c];
                if (saCount == 0L) { // radius hasn't changed
                    maxRadius =  L1CSGSimple.INIT_RADIUS;
                    continue;
                }
                double oldRadius = empiricalGame.getRadius(s, c);
                double deltaSlot = deltaContain / (empiricalGame.getNumChoices() * saCount * (saCount + 1.0));
                double radius = helper.weissmanRadius(empiricalGame, saCount, deltaSlot);
                if (radius > maxRadius) {
                    maxRadius = radius;
                }
                empiricalGame.setRadius(s, c, radius);
                explorationRMDP.setRadius(s, c, radius);
                totalRadius += (radius - oldRadius);

                updateExplorationReward(s, c);
                updateKnown(s, c);

                int sFinal = s;
                int cFinal = c;

                empiricalGame.getSuccessorsIterator(s, c).forEachRemaining(succ -> {
                    double pHat = transitionCounts[sFinal][cFinal][succ] / (double) saCount;
                    pHat = Math.max(L1CSGSimple.TRANS_PROB_LB, pHat);
                    empiricalGame.setCentre(sFinal, cFinal, succ, pHat);
                    explorationRMDP.setCentre(sFinal, cFinal, succ, pHat);
                });
            }
        }
    }

    private double computeDeltaT(double rMax, int horizon) {
        return 0.5 * rMax * horizon * horizon * maxRadius;
    }

    private void initialiseMCs(PropertiesFile propertiesFile, Property property, boolean zeroSum) throws PrismException {
        StateModelChecker ucsgMC = explicit.StateModelChecker.createModelChecker(empiricalGame.getModelType(), prism);
        if (!(ucsgMC instanceof UCSGModelChecker)) {
            throw new PrismException("Expected a UCSGModelChecker for robust solving, but got " + ucsgMC.getClass().getSimpleName());
        }

        robustMC = (UCSGModelChecker) ucsgMC;
        robustMC.setModelCheckingInfo(prism.getModelInfo(), propertiesFile, prism.getRewardGenerator());
        robustMC.setGenStrat(prism.getGenStrat());
        robustMC.setSilentPrecomputations(true);
        robustMC.setVerbosity(0);
        robustSolveL1CSG(property); // solve once so that the model checker records the target states

        StateModelChecker csgMC = explicit.StateModelChecker.createModelChecker(empiricalGame.getCentreCSG().getModelType(), prism);
        if (!(csgMC instanceof CSGModelChecker)) {
            throw new PrismException("Expected a CSGModelChecker for solving point model, but got " + csgMC.getClass().getSimpleName());
        }

        pointMC = (CSGModelChecker) csgMC;
        pointMC.setModelCheckingInfo(prism.getModelInfo(), propertiesFile, prism.getRewardGenerator());
        pointMC.setGenStrat(prism.getGenStrat());
        pointMC.setSilentPrecomputations(true);
        pointMC.setVerbosity(0);
        solvePointModel(property); // solve once so that the model checker records the target states

        helper.targets = robustMC.getTargets();
        if (zeroSum) {
            helper.rewards = new ArrayList<>();
            helper.rewards.add((CSGRewards<Double>) robustMC.getReward());
        } else  {
            helper.rewards = robustMC.getRewards();
        }
    }

    private SolveOutcome solvePointModel(Property property) {
        return helper.getSolveOutcome(pointMC, empiricalGame.getCentreCSG(), property);
    }

    private SolveOutcome robustSolveL1CSG(Property property) {
        return helper.getSolveOutcome(robustMC, empiricalGame, property);
    }

    private SolveOutcome solveTrueGame(PropertiesFile propertiesFile, Property property) throws PrismException {
        StateModelChecker mc = StateModelChecker.createModelChecker(trueGame.getModelType(), prism);
        if (!(mc instanceof CSGModelChecker)) {
            throw new PrismException("Expected a CSGModelChecker, but got " + mc.getClass().getSimpleName());
        }

        CSGModelChecker trueMC = (CSGModelChecker) mc;
        trueMC.setModelCheckingInfo(prism.getModelInfo(), propertiesFile, prism.getRewardGenerator());
        trueMC.setGenStrat(prism.getGenStrat());
        trueMC.setSilentPrecomputations(false);

        return helper.getSolveOutcome(trueMC, trueGame, property);
    }

    private void solveExplorationRMDP() throws PrismException {
        UMDPModelChecker mc = new UMDPModelChecker(this.prism);
        mc.setGenStrat(true);
        mc.setPrecomp(true);
        mc.setSilentPrecomputations(true);
        mc.setVerbosity(0);

        ModelCheckerResult res = mc.computeCumulativeRewards(explorationRMDP, explorationRewards, effHorizon, MinMax.max().setMinUnc(true));

        if (res == null || res.strat == null) {
            throw new PrismException("Exploration solver did not return a strategy.");
        }

        explorationStrat = (Strategy<Double>) res.strat;
        if (!(explorationStrat instanceof StrategyGenerator)) {
            throw new PrismException("Exploration strategy must be a StrategyGenerator for the simulator");
        }
        helper.sim.loadStrategy((StrategyGenerator<Double>) explorationStrat);
        helper.sim.setStrategyEnforced(true);
    }


    private double computeMinReachProb() throws PrismException {
        double minProb = 1.0;
        BitSet target = new BitSet();
        for (int s = 0; s < empiricalGame.getNumStates(); s++) {
            target.clear();
            target.set(s);
            double prob = helper.computeReachProbs(prism, empiricalGame, target, MinMax.max())[empiricalGame.getFirstInitialState()];
            if (prob < minProb) {
                minProb = prob;
            }
        }
        return minProb;
    }

    private double computeStopProb() throws PrismException {
        // target is now union of the players' targets
        BitSet target = helper.getTargetUnion();
        double[] sol = helper.computeReachProbs(prism, empiricalGame, target, MinMax.minMin(true, true));
        double pStop = DoubleStream.of(sol)
                .filter(x -> x > TRANS_PROB_LB)
                .min()
                .orElse(Double.NaN);
        if (pStop == Double.NaN) {
            throw new PrismException("Stopping probability assumption violated");
        }
        return pStop;
    }

    private void verifyInTrueGame(SolveOutcome sol) throws Exception {
        if (sol.getStrategy() == null) {
            String reason = helper.spec.finiteHorizon ? "strategy generation only supported for infinite-horizon properties" : "strategy generation is disabled for Prob1 precomputation";
            System.out.println("No strategy returned (" + reason + "). Skipping true value computation.");
        } else if (!sol.foundRNE()) {
            System.out.println("No exact NE found. Skipping true value computation.");
        } else {
            trueValue = helper.computeCSGValue(prism, trueGame, sol.getStrategy());
            System.out.println("True value of learned strategy: " + trueValue);
            System.out.println("Value gap: " + (trueValue - sol.getValue()));
            double trueDevGain = computeNashMargin(sol);
            System.out.println("Max deviation gain of learned strategy: " + trueDevGain);
        }
    }

    protected double computeNashMargin(SolveOutcome sol) throws Exception {
        double eqVal = helper.computeCSGValue(prism, trueGame, sol.getStrategy());
        // for zero-sum this is just true value - value under the given strategy
        if (helper.spec.zeroSum) {
            if (trueValue == Double.NaN) trueValue = solveTrueGame(helper.spec.propertiesFile, helper.spec.property).getValue();
            return trueValue - sol.getValue();
        }
        int s0 = trueGame.getFirstInitialState();
        // extract per-player strategies
        List<Map<BitSet, Double>> strat = helper.extractNEStrategy(sol.getStrategy(), s0);
        // compute equilibrium value in true game
        double maxMargin = 0.0;
        for (int p = 0; p < strat.size(); p++) {
            double devVal = trueGame.computeDeviationValue(p, strat, helper.rewards, trueGame.getIndexes(), s0);
            maxMargin = Math.max(maxMargin, devVal - eqVal);
        }

        return maxMargin;
    }


    private void printResult(long duration, PacResult result, Experiment.PacRunSpec spec) throws PrismException, InvalidStrategyStateException, Exception {
        System.out.println("\n---------------------------------------");
        System.out.println("Epsilon: " + spec.epsilon);
        System.out.println("Confidence: " + spec.confidence);
        System.out.println();
        System.out.println("Execution time: " + duration  / 1e6 + " ms");
        System.out.println("Episodes=" + result.episodes);
        System.out.println("DeltaT=" + result.deltaT);
        System.out.println("nMin=" + this.nMin);

        System.out.println("\n---------------------------------------");
        System.out.println("Robust " + result.robustSol); // prints "Robust SolveOutcome{...}"
        // evaluate robust vs. point policy in true game
        System.out.println("Evaluating robust strategy in true CSG...");
        verifyInTrueGame(result.robustSol);

        System.out.println("\n---------------------------------------");
        System.out.println("Point " + result.pointSol);
        System.out.println("Evaluating point strategy in true CSG...");
        verifyInTrueGame(result.pointSol);

        // compare to true value
        System.out.println("\n---------------------------------------");
        SolveOutcome trueSol = solveTrueGame(spec.propertiesFile, spec.property);
        System.out.println("True " + trueSol);
        System.out.println("---------------------------------------");

    }


    public static void main(String[] args) throws Exception {
        Prism prism = new Prism();
        prism.initialise();
        prism.useNative();

        Experiment ex = new Experiment(Experiment.CaseStudy.SAFE_RISKY);
        ex.setSolverString("Yices");
        ex.propertyIndex = 1;
        Experiment.PacRunSpec spec = ex.buildPacRunSpec(prism);

        PACLearner learner = new PACLearner(prism, 41, true);
        long start = System.nanoTime();
        PacResult res = learner.runPacLoop(spec, ex.modelFile, ex.propertyIndex);
        long end = System.nanoTime();
        long duration = end - start;

        learner.printResult(duration, res, spec);
    }
}