package learning;

import explicit.*;
import explicit.rewards.CSGRewards;
import explicit.rewards.MDPRewardsSimple;
import org.apache.commons.math3.util.Precision;
import parser.ast.*;
import prism.*;
import strat.*;
import learning.LearningHelper.SolveOutcome;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.stream.DoubleStream;


public class PACLearner {

    public static final double NASH_STOP_THRESH = 4.0;
    public static final double ZERO_SUM_STOP_THRESH = 2.0;
    private static final double TRANS_PROB_LB = 1e-6;
    private static final String DEFAULT_SMT_SOLVER = "Yices";

    public static final class PacResult {
        public final boolean noExactNE;
        public final int episodes;
        public final double robustValue;
        public final double deltaT;
        public final CSGStrategy<Double> robustStrategy;

        public PacResult(boolean noExactNE,
                         int episodes,
                         double robustValue,
                         double deltaT,
                         CSGStrategy<Double> robustStrategy) {
            this.noExactNE = noExactNE;
            this.episodes = episodes;
            this.robustValue = robustValue;
            this.deltaT = deltaT;
            this.robustStrategy = robustStrategy;
        }
    }

    private final Prism prism;

    private long[][] slotCounts;
    private long[][][] transitionCounts; // indexed by [s][c], then maps successor state index to count; 3rd dimension might be sparse
    private boolean[][] known;
    int numUnknownSlots;
    int prevNumUnknownSlots;

    private CSGSimple<Double> trueGame;
    private L1CSGSimple<Double> empiricalGame;
    private L1MDPSimple<Double> explorationRMDP;
    private long nMin;
    private double maxRadius = L1CSGSimple.INIT_RADIUS;
    private MDPRewardsSimple<Double> explorationRewards;
    private UCSGModelChecker empiricalMC;
    private Strategy<Double> explorationStrat;

    private int episode;
    private final LearningHelper helper = new LearningHelper();

    /**
     * The effective horizon
     * For finite-horizon properties, this is the property's upper bound.
     * For unbounded properties, this is the fallback rollout cap.
     */
    private int horizon;

    public PACLearner(Prism prism, int seed) throws PrismException {
        this.prism = prism;
        this.prism.setSimulatorSeed(seed);
        this.prism.setGenStrat(true);
    }

    public PacResult runPacLoop(Experiment.PacRunSpec spec) throws PrismException {
        return runPacLoop(
                spec.trueGame,
                spec.propertiesFile,
                spec.property,
                spec.epsilon,
                spec.confidence,
                spec.rMax,
                spec.horizon,
                spec.solverString,
                spec.zeroSum,
                spec.finiteHorizon
        );
    }

    public PacResult runPacLoop(
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
        final double deltaCov= confidence / 2.0;
        episode = 1;
        this.trueGame = trueGame;
        initialiseRun(solver, propertiesFile, property, zeroSum);

        if (!finiteHorizon) {
            double pT = computeStopProb();
            System.out.println("Stopping probability: " + pT);
            this.horizon = (int) Math.ceil(trueGame.getNumStates() / pT);
            System.out.println("Effective horizon: " + this.horizon);
        } else {
            this.horizon = horizon;
        }

        double pReach = computeMinReachProb();
        System.out.println("Lower bound on reachability probability: " + pReach);

        double stopThreshFactor = zeroSum ? ZERO_SUM_STOP_THRESH : NASH_STOP_THRESH;
        computeNmin(deltaContain, rMax, eps, stopThreshFactor);
        double stopThresh = eps / stopThreshFactor;
        double deltaT;

        while (true) {
            deltaT = computeDeltaT(rMax, horizon);
            if (deltaT <= stopThresh  || numUnknownSlots == 0) {
                SolveOutcome robustSol = robustSolveL1CSG(property);
                if (!robustSol.found) {
                    if (zeroSum) throw new PrismException("No NE found but zero-sum property should always have an NE");
                    return new PacResult(true, episode, robustSol.value, deltaT, robustSol.strategy);
                } else {
                    return new PacResult(false, episode, robustSol.value, deltaT, robustSol.strategy);
                }
            }

            if (episode == 1 || prevNumUnknownSlots != numUnknownSlots) { // only re-solve the exploration RMDP if radii of the worst slots change
                System.out.println("Episode " + episode + ": Resolving exploration RMDP");
                solveExplorationRMDP();
                prevNumUnknownSlots = numUnknownSlots;
            }

            int numSamples = computeNumSamples(deltaCov, pReach);
//            System.out.println("Episode " + episode + ": Sampling " + numSamples + " trajectories with current exploration strategy...");
            for (int i = 0; i < numSamples; i++)
                helper.sampleTrajectory(horizon, this::updateCount);

            update(deltaContain);
//            printEpisodeResult(episode, deltaT);
            episode++;
        }
    }

    private int computeNumSamples(double deltaCov, double pReach) {
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

        double c = -stopThreshFactor * stopThreshFactor * rMax * rMax * Math.pow(horizon, 4.0) / (eps * eps);
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

        empiricalGame = new L1CSGSimple<>(trueGame, trans);
        explorationRMDP = new L1MDPSimple<>(empiricalGame);
        explorationRewards = new MDPRewardsSimple<>(explorationRMDP.getNumStates());
        String chosenSolver = (solver == null || solver.isBlank()) ? DEFAULT_SMT_SOLVER : solver;
        prism.getSettings().set(PrismSettings.PRISM_SMT_SOLVER, chosenSolver);

        helper.setStateToIndex(trueGame);
        initialiseMC(propertiesFile, property, zeroSum);
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
                if (saCount == 0L) {
                    maxRadius =  L1CSGSimple.INIT_RADIUS;
                    continue;
                }
                double deltaSlot = deltaContain / (empiricalGame.getNumChoices() * saCount * (saCount + 1.0));
                double radius = helper.weissmanRadius(empiricalGame, saCount, deltaSlot);
                if (radius > maxRadius) {
                    maxRadius = radius;
                }
                empiricalGame.setRadius(s, c, radius);
                explorationRMDP.setRadius(s, c, radius);

                updateExplorationReward(s, c);
                updateKnown(s, c);

                int sFinal = s;
                int cFinal = c;

                empiricalGame.getSuccessorsIterator(s, c).forEachRemaining(succ -> {
                    double pHat = transitionCounts[sFinal][cFinal][succ] / (double) saCount;
                    pHat = Math.max(TRANS_PROB_LB, pHat);
                    empiricalGame.setCentre(sFinal, cFinal, succ, pHat);
                    explorationRMDP.setCentre(sFinal, cFinal, succ, pHat);
                });
            }
        }
    }

    private double computeDeltaT(double rMax, int horizon) {
        return 0.5 * rMax * horizon * horizon * maxRadius;
    }

    private void initialiseMC(PropertiesFile propertiesFile, Property property, boolean zeroSum) throws PrismException {
        StateModelChecker mc = explicit.StateModelChecker.createModelChecker(empiricalGame.getModelType(), prism);
        if (!(mc instanceof UCSGModelChecker)) {
            throw new PrismException("Expected a UCSGModelChecker for robust solving, but got " + mc.getClass().getSimpleName());
        }

        empiricalMC = (UCSGModelChecker) mc;
        empiricalMC.setModelCheckingInfo(prism.getModelInfo(), propertiesFile, prism.getRewardGenerator());
        empiricalMC.setGenStrat(true);
        empiricalMC.setSilentPrecomputations(true);
        empiricalMC.setVerbosity(0);

        robustSolveL1CSG(property); // solve once so that the model checker records the target states
        helper.targets = zeroSum ? new BitSet[]{empiricalMC.getTarget()} : empiricalMC.getTargets();
        if (zeroSum) {
            helper.rewards = new ArrayList<>();
            helper.rewards.add((CSGRewards<Double>) empiricalMC.getReward());
        } else
            helper.rewards = empiricalMC.getRewards();
    }

    private SolveOutcome robustSolveL1CSG(Property property) {
        return helper.getSolveOutcome(empiricalMC, empiricalGame, property);
    }

    private SolveOutcome solveTrueGame(PropertiesFile propertiesFile, Property property) throws PrismException {
        StateModelChecker mc = StateModelChecker.createModelChecker(trueGame.getModelType(), prism);
        if (!(mc instanceof CSGModelChecker)) {
            throw new PrismException("Expected a CSGModelChecker, but got " + mc.getClass().getSimpleName());
        }

        CSGModelChecker trueMC = (CSGModelChecker) mc;
        trueMC.setModelCheckingInfo(prism.getModelInfo(), propertiesFile, prism.getRewardGenerator());
        trueMC.setGenStrat(true);
        trueMC.setSilentPrecomputations(false);

        return helper.getSolveOutcome(trueMC, trueGame, property);
    }

    private void solveExplorationRMDP() throws PrismException {
        UMDPModelChecker mc = new UMDPModelChecker(this.prism);
        mc.setGenStrat(true);
        mc.setPrecomp(true);
        mc.setSilentPrecomputations(true);
        mc.setVerbosity(0);

        ModelCheckerResult res = mc.computeCumulativeRewards(explorationRMDP, explorationRewards, horizon, MinMax.max().setMinUnc(true));

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
        double[] sol = helper.computeReachProbs(prism, empiricalGame, helper.getTargetUnion(), MinMax.max());
        double pStop = DoubleStream.of(sol).min().orElse(0.0);
        if (Precision.equals(pStop, 0.0)) {
            throw new PrismException("Stopping probability for target is 0 - assumption violated");
        }
        return pStop;
    }

    private double computeTrueValue(CSGStrategy<Double> strategy) throws PrismException, InvalidStrategyStateException {
        return helper.computeCSGValue(prism, trueGame, strategy);
    }


    public static void main(String[] args) throws Exception {
        Prism prism = new Prism();
        prism.initialise();
        prism.useNative();

        Experiment ex = new Experiment(Experiment.CaseStudy.SAFE_RISKY);
        ex.setSolverString("Yices");

        Experiment.PacRunSpec spec = ex.buildPacRunSpec(prism);
        ex.propertyIndex = 2;

        PACLearner learner = new PACLearner(prism, 41);
        long start = System.nanoTime();
        PacResult res = learner.runPacLoop(spec);
        long end = System.nanoTime();
        long duration = end - start;

        System.out.println("\n---------------------------------------");

        System.out.println("Execution time: " + duration  / 1e6 + " milliseconds");

        System.out.println("noExactNE=" + res.noExactNE);
        System.out.println("episodes=" + res.episodes);
        System.out.println("robustValue=" + res.robustValue);
        System.out.println("deltaT=" + res.deltaT);
        System.out.println("nMin=" + learner.nMin);

        System.out.println("---------------------------------------");
        SolveOutcome trueSol = learner.solveTrueGame(spec.propertiesFile, spec.property);
        System.out.println("True value: " + trueSol);

        System.out.println("---------------------------------------");

        // check true value of the returned policy
        if (res.robustStrategy == null) {
            System.out.println("No robust strategy returned, skipping true value computation.");
        } else if (res.noExactNE) {
            System.out.println("No exact NE found, so returned strategy may not be valid. Skipping true value computation.");
        } else {
            double trueValue = learner.computeTrueValue(res.robustStrategy);
            System.out.println("True value of returned strategy: " + trueValue);
        }
    }
}