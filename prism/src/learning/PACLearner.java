package learning;

import explicit.*;
import explicit.rewards.CSGRewards;
import explicit.rewards.MDPRewardsSimple;
import parser.ast.*;
import prism.*;
import strat.*;
import learning.PACHelper.SolveOutcome;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.DoubleStream;


public class PACLearner {

    public static final double NASH_STOP_THRESH = 4.0;
    public static final double ZERO_SUM_STOP_THRESH = 2.0;
    private static final String DEFAULT_SMT_SOLVER = "Yices";
    private static final double TRANS_PROB_LB = 1e-5;
    public static final String ROBUST_SUFFIX = "_robust";
    public static final String TRUE_SUFFIX = "_true";
    public static final String POINT_SUFFIX = "_point";
    public static final int NUM_COALITIONS = 2;

    public static final class PacResult {
        public final int episodes;
        public final double deltaT;
        public final SolveOutcome robustSol;
        public final SolveOutcome pointSol;

        public PacResult(int episodes,
                         double deltaT,
                         SolveOutcome robustSol,
                         SolveOutcome pointSol
                         ) {
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

    private int episode;
    private final PACHelper helper = new PACHelper();
    private Logger logger;
    private double totalRadius = 0.0;
    private long totalNumSamples = 0;
    private long totalNumTransitions = 0;

    // === Experiment extensions (defaults preserve original behaviour) ===
    private ExplorationPolicy explorationPolicy = ExplorationPolicy.RMDP;
    private int valueGapEvery = 0; // if > 0, solve the empirical game every X episodes and log the true value gap
    private String runLabel = ""; // appended to per-episode log filenames (e.g. seed/explorer tags for batch runs)
    private L1MDPSimple<Double> pointMDP; // zero-radius (point-estimate) MDP used by ROUND_ROBIN navigation
    private int rrTargetS = -1, rrTargetC = -1; // current round-robin target slot
    private double trueValueForGap = Double.NaN; // cached V* for periodic value-gap logging

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
        helper.rng = new Random(seed);
    }

    public void setExplorationPolicy(ExplorationPolicy policy) {
        this.explorationPolicy = policy;
    }

    public void setValueGapEvery(int valueGapEvery) {
        this.valueGapEvery = valueGapEvery;
    }

    public void setRunLabel(String runLabel) {
        this.runLabel = (runLabel == null) ? "" : runLabel;
    }

    public long getTotalNumSamples() {
        return totalNumSamples;
    }

    public long getTotalNumTransitions() {
        return totalNumTransitions;
    }

    public long getNMin() {
        return nMin;
    }

    public PacResult runPacLoop(Experiment.PacRunSpec spec, String modelFilePath, int propertyIndex, boolean robustnessExperiment, String logSubdir) throws PrismException {
        helper.spec = spec;
        logger = new Logger(modelFilePath, propertyIndex, logSubdir, runLabel);
        System.out.println("Property: " + spec.property);

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
                spec.finiteHorizon,
                spec.maxNumSamples,
                robustnessExperiment
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
            boolean finiteHorizon,
            int maxNumSamples,
            boolean robustnessExperiment
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
        if (effHorizon == 1) { // corner case - only transitions from s0 matter
            initialiseNumUnknownSlots(empiricalGame.getNumChoices(empiricalGame.getFirstInitialState()));
        }

        double pReach = computeMinReachProb();
        System.out.println("Lower bound on reachability probability: " + pReach);

        System.out.println("Exploration policy: " + explorationPolicy);
        if (explorationPolicy == ExplorationPolicy.ROUND_ROBIN) {
            initialisePointMDP();
        }
        if (valueGapEvery > 0) {
            SolveOutcome trueSol = solveTrueGame(propertiesFile, property);
            trueValueForGap = (trueSol != null && trueSol.foundNE()) ? trueSol.getValue() : Double.NaN;
            System.out.println("Periodic value-gap logging every " + valueGapEvery + " episodes (V* = " + trueValueForGap + ")");
        }

        double stopThreshFactor = zeroSum ? ZERO_SUM_STOP_THRESH : NASH_STOP_THRESH;
        computeNmin(deltaContain, rMax, eps, stopThreshFactor);
        System.out.println("nMin: " + nMin);
        double stopThresh = eps / stopThreshFactor;
        double deltaT;

        while (true) {
            deltaT = computeDeltaT(rMax, effHorizon);

            if ((robustnessExperiment && totalNumSamples > maxNumSamples) || (deltaT <= stopThresh || numUnknownSlots == 0)) {
                logger.close(); // ===== NEW =====
                SolveOutcome robustSol = robustSolveL1CSG(property);
                SolveOutcome pointSol = solvePointModel(property);
                return new PacResult(episode-1, deltaT, robustSol, pointSol);
            }

            switch (explorationPolicy) {
                case UNIFORM -> {
                    // no exploration strategy => simulator samples joint actions uniformly at random
                    helper.explorationStrategy = null;
                }
                case ROUND_ROBIN -> updateRoundRobinExploration();
                default -> { // RMDP (pessimistic) and OPTIMISTIC
                    if (episode == 1) {
                        solveExplorationRMDP();
                    } else if (prevNumUnknownSlots != numUnknownSlots) {
                        System.out.println("Episode " + episode + ": Resolving exploration RMDP");
                        solveExplorationRMDP();
                        prevNumUnknownSlots = numUnknownSlots;
                    }
                }
            }

            int numSamples = computeNumSamples(deltaCov, episode, pReach);
            totalNumSamples += numSamples;

            for (int i = 0; i < numSamples; i++)
                helper.sampleTrajectory(effHorizon, this::updateCount);

            update(deltaContain);
            boolean logRow = (episode - 1) % 10 == 0;
            double trueValueGap = Double.NaN;
            if (valueGapEvery > 0 && (episode - 1) % valueGapEvery == 0) {
                trueValueGap = computeTrueValueGap(property);
                logRow = true;
            }
            if (logRow)
                logger.logEpisode(episode, deltaT, numSamples, totalNumSamples, maxRadius, totalRadius / totalNumSlots, numUnknownSlots, (double) numUnknownSlots / totalNumSlots, computeModelErrorL1(), trueValueGap);
            episode++;
        }
    }

    private int computeNumSamples(double deltaCov, int episode, double pReach) {
        double invDeltaEpisode = (episode * (episode + 1.0)) / deltaCov;
        return (int) Math.ceil(Math.log(invDeltaEpisode) * Math.min(numUnknownSlots, 1.0 / pReach));
//        return (int) Math.ceil(- Math.log(deltaEpisode) * Math.max(1, numUnknownSlots));
    }


    private void updateCount(int s, int c, int succ) {
        slotCounts[s][c] += 1L;
        long[] counts = transitionCounts[s][c];
        counts[succ] += 1L;
        totalNumTransitions += 1L;
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

        initialiseNumUnknownSlots(numUnknownSlots);

        empiricalGame = new L1CSGSimple<>(trueGame, trans);
        explorationRMDP = new L1MDPSimple<>(empiricalGame);
        explorationRewards = new MDPRewardsSimple<>(explorationRMDP.getNumStates());
        // All slots start unknown with radius INIT_RADIUS, so they must start with the
        // corresponding exploration reward; update() only refreshes rewards of sampled slots,
        // and without initialisation never-visited slots would keep reward 0 and never attract
        // the exploration strategy.
        for (int s = 0; s < numStates; s++) {
            for (int c = 0; c < trueGame.getNumChoices(s); c++) {
                explorationRewards.setTransitionReward(s, c, L1CSGSimple.INIT_RADIUS * L1CSGSimple.INIT_RADIUS);
            }
        }
        String chosenSolver = (solver == null || solver.isBlank()) ? DEFAULT_SMT_SOLVER : solver;
        prism.getSettings().set(PrismSettings.PRISM_SMT_SOLVER, chosenSolver);

        helper.setStateToIndex(trueGame);
        initialiseMCs(propertiesFile, property, zeroSum);
        prism.loadModelIntoSimulator();
        helper.sim = prism.getSimulator();
    }

    private void initialiseNumUnknownSlots(int numUnknownSlots) {
        this.numUnknownSlots = numUnknownSlots;
        prevNumUnknownSlots = numUnknownSlots;
        totalNumSlots = numUnknownSlots;
        totalRadius = totalNumSlots * L1CSGSimple.INIT_RADIUS;
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
            if (this.effHorizon == 1 && s != empiricalGame.getFirstInitialState()) {
                continue; // only transitions from initial state matter for 1-step properties
            }
            for (int c = 0; c < empiricalGame.getNumChoices(s); c++) {
                long saCount = slotCounts[s][c];
                if (saCount == 0L) { // radius hasn't changed
                    maxRadius =  L1CSGSimple.INIT_RADIUS;
                    continue;
                }
                double oldRadius = empiricalGame.getRadius(s, c);
                double deltaSlot = deltaContain / (totalNumSlots * saCount * (saCount + 1.0));
                double radius = helper.weissmanRadius(empiricalGame, saCount, deltaSlot);
                if (radius > maxRadius) {
                    maxRadius = radius;
                }
                empiricalGame.setRadius(s, c, radius);
                explorationRMDP.setRadius(s, c, radius);
                totalRadius += (radius - oldRadius);

                // updateKnown must run first: the exploration RMDP is only re-solved when a slot
                // flips to known, so the re-solve must not see the flipped slot's stale positive
                // reward (else it deterministically targets an already-known slot and, with no
                // further flips to trigger a re-solve, starves the remaining unknown slots forever).
                updateKnown(s, c);
                updateExplorationReward(s, c);

                Set<Integer> succs = new HashSet<>(empiricalGame.getChoice(s, c).getSupport()); // snapshot of empiricalGame.getSuccessorsIterator
                for (int succ : succs) {
                    double pHat = transitionCounts[s][c][succ] / (double) saCount;
                    pHat = Math.max(L1CSGSimple.TRANS_PROB_LB, pHat);

                    empiricalGame.setCentre(s, c, succ, pHat);
                    explorationRMDP.setCentre(s, c, succ, pHat);
                    if (pointMDP != null) pointMDP.setCentre(s, c, succ, pHat);
                }
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
//        robustMC.checkExpression(empiricalGame, property.getExpression(), null, true);
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
//        pointMC.checkExpression(empiricalGame.getCentreCSG(), property.getExpression(), null, true);
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
        // pessimistic (min over uncertainty set) for RMDP, optimistic (max) for OPTIMISTIC
        boolean pessimistic = explorationPolicy != ExplorationPolicy.OPTIMISTIC;
        solveExploration(explorationRMDP, explorationRewards, MinMax.max().setMinUnc(pessimistic));
    }

    private void solveExploration(UMDP<Double> model, MDPRewardsSimple<Double> rewards, MinMax minMax) throws PrismException {
        UMDPModelChecker mc = new UMDPModelChecker(this.prism);
        mc.setGenStrat(true);
        mc.setPrecomp(true);
        mc.setSilentPrecomputations(true);
        mc.setVerbosity(0);

        ModelCheckerResult res = mc.computeCumulativeRewards(model, rewards, effHorizon, minMax);

        if (res == null || res.strat == null) {
            throw new PrismException("Exploration solver did not return a strategy.");
        }

        explorationStrat = (Strategy<Double>) res.strat;
        // Enforced by choice index during trajectory sampling (see PACHelper.sampleTrajectory);
        // action-label-based enforcement via the simulator is unreliable for CSG joint actions.
        helper.explorationStrategy = explorationStrat;
    }

    /** Zero-radius copy of the empirical model, used by ROUND_ROBIN to navigate on point estimates. */
    private void initialisePointMDP() {
        pointMDP = new L1MDPSimple<>(empiricalGame);
        for (int s = 0; s < pointMDP.getNumStates(); s++) {
            for (int c = 0; c < pointMDP.getNumChoices(s); c++) {
                pointMDP.setRadius(s, c, 0.0);
            }
        }
        rrTargetS = -1;
        rrTargetC = -1;
    }

    /**
     * Round-robin baseline: target the unknown (s,a) slot with the fewest samples and
     * play a point-estimate MDP strategy maximising the expected number of visits to it.
     * The strategy is only re-solved when the target slot changes.
     */
    private void updateRoundRobinExploration() throws PrismException {
        int bestS = -1, bestC = -1;
        long bestCount = Long.MAX_VALUE;
        for (int s = 0; s < empiricalGame.getNumStates(); s++) {
            if (effHorizon == 1 && s != empiricalGame.getFirstInitialState()) continue;
            for (int c = 0; c < empiricalGame.getNumChoices(s); c++) {
                if (!known[s][c] && slotCounts[s][c] < bestCount) {
                    bestCount = slotCounts[s][c];
                    bestS = s;
                    bestC = c;
                }
            }
        }
        if (bestS < 0) return; // all slots known; main loop terminates via numUnknownSlots == 0
        if (bestS == rrTargetS && bestC == rrTargetC && explorationStrat != null) return;

        rrTargetS = bestS;
        rrTargetC = bestC;
        System.out.println("Episode " + episode + ": Round-robin target slot (" + bestS + "," + bestC + ")");
        MDPRewardsSimple<Double> targetReward = new MDPRewardsSimple<>(pointMDP.getNumStates());
        targetReward.setTransitionReward(bestS, bestC, 1.0);
        solveExploration(pointMDP, targetReward, MinMax.max().setMinUnc(true)); // radius 0 => point model
    }

    /** Max over (s,a) slots of the L1 distance between the empirical centre and the true kernel. */
    private double computeModelErrorL1() {
        double maxErr = 0.0;
        for (int s = 0; s < trueGame.getNumStates(); s++) {
            if (effHorizon == 1 && s != empiricalGame.getFirstInitialState()) continue;
            for (int c = 0; c < trueGame.getNumChoices(s); c++) {
                Distribution<Double> pTrue = trueGame.getChoice(s, c);
                Distribution<Double> pHat = empiricalGame.getChoice(s, c);
                Set<Integer> support = new HashSet<>(pTrue.getSupport());
                support.addAll(pHat.getSupport());
                double err = 0.0;
                for (int succ : support) {
                    double t = pTrue.contains(succ) ? pTrue.get(succ) : 0.0;
                    double h = pHat.contains(succ) ? pHat.get(succ) : 0.0;
                    err += Math.abs(t - h);
                }
                maxErr = Math.max(maxErr, err);
            }
        }
        return maxErr;
    }

    /**
     * Periodically solve the empirical robust game and evaluate the learned profile in the true game.
     * Returns V* - u(sigma_t, P*), or NaN if unavailable (no NE found / no strategy generated).
     */
    private double computeTrueValueGap(Property property) {
        try {
            SolveOutcome sol = robustSolveL1CSG(property);
            if (sol == null || !sol.foundNE() || sol.getStrategy() == null || Double.isNaN(trueValueForGap)) {
                return Double.NaN;
            }
            double v = helper.computeValueInCSG(prism, trueGame, sol.getStrategy());
            return trueValueForGap - v;
        } catch (Exception e) {
            return Double.NaN;
        }
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
        double[] sol = helper.computeReachProbs(prism, empiricalGame, target, MinMax.minMin(false, true));
        double pStop = DoubleStream.of(sol)
                .filter(x -> x > TRANS_PROB_LB)
                .min()
                .orElse(Double.NaN);
        if (Double.isNaN(pStop)) {
            throw new PrismException("Stopping probability assumption violated");
        }
        return pStop;
    }

    private void verifyInTrueGame(SolveOutcome sol, SolveOutcome trueSol, boolean exportStrat, String modelFilePath, int propertyIndex, boolean robustnessExperiment, String strategyFileSuffix, String subdirName) throws Exception {
        if (helper.spec.finiteHorizon && sol.getStrategy() == null) {
//            String reason = helper.spec.finiteHorizon ? "strategy generation only supported for infinite-horizon properties" : "strategy generation is disabled for Prob1 precomputation";
            System.out.println("Strategy generation only supported for infinite-horizon properties. Skipping true value computation.");
        } else if (!sol.foundNE()) {
            System.out.println("No exact NE found. Skipping true value computation.");
        } else {
            double valueInTrueGame, valueGap;
            if (trueSol.getStrategy() != null && trueSol.getStrategy().sameChoices(sol.getStrategy())) {
                System.out.println("Learned strategy has same choices as true NE, so value in true game is the same as in empirical game.");
                valueInTrueGame = trueSol.getValue();
                valueGap = 0.0;
            } else {
                valueInTrueGame = helper.computeValueInCSG(prism, trueGame, sol.getStrategy());
                valueGap = trueSol.getValue() - valueInTrueGame;
            }
            System.out.println("True value of learned strategy: " + valueInTrueGame);
            System.out.println("Estimation error: " + (valueInTrueGame - sol.getValue()));
            System.out.println("Value gap: " + valueGap);
//            double trueDevGain = computeNashMargin(sol, trueSol.getValue());
//            System.out.println("Max deviation gain: " + trueDevGain);

            if (exportStrat) {
                sol.getStrategy().exportToFile(getExportStrategyFile(modelFilePath, propertyIndex, robustnessExperiment, strategyFileSuffix, subdirName));
            }
        }
    }

//    protected double computeNashMargin(SolveOutcome sol, double trueValue) throws Exception {
//        double eqVal = helper.computeValueInCSG(prism, trueGame, sol.getStrategy());
//        // for zero-sum this is just true value - value under the given strategy
//        if (helper.spec.zeroSum) {
////            if (Double.isNaN(trueValue)) trueValue = solveTrueGame(helper.spec.propertiesFile, helper.spec.property).getValue();
//            return trueValue - sol.getValue();
//        }
//        int s0 = trueGame.getFirstInitialState();
//        // extract per-player strategies
//        List<Map<BitSet, Double>> strat = helper.extractNEStrategy(sol.getStrategy(), s0);
//        // compute equilibrium value in true game
//        double maxMargin = 0.0;
//        for (int p = 0; p < strat.size(); p++) {
//            double devVal = trueGame.computeDeviationValue(p, strat, helper.rewards, trueGame.getIndexes(), s0);
//            maxMargin = Math.max(maxMargin, devVal - eqVal);
//        }
//
//        return maxMargin;
//    }


    void printResult(long duration, PacResult result, boolean exportStrat, String modelFilePath, int propertyIndex, boolean robustnessExperiment, String subdirName) throws PrismException, InvalidStrategyStateException, Exception {
        System.out.println("\n---------------------------------------");
        System.out.println("Epsilon: " + helper.spec.epsilon);
        System.out.println("Confidence: " + helper.spec.confidence);
        System.out.println();
        System.out.println("Execution time: " + duration  / 1e6 + " ms");
        System.out.println("Episodes=" + result.episodes);
        System.out.println("DeltaT=" + result.deltaT);
        System.out.println("nMin=" + this.nMin);
        System.out.println("Total number of samples: " + this.totalNumSamples);

        // compare to true value
        System.out.println("\n---------------------------------------");
        SolveOutcome trueSol = solveTrueGame(helper.spec.propertiesFile, helper.spec.property);
        if (trueSol != null) {
            System.out.println("True " + trueSol);
            if (exportStrat && trueSol.getStrategy() != null) {
                trueSol.getStrategy().exportToFile(getExportStrategyFile(modelFilePath, propertyIndex, robustnessExperiment, TRUE_SUFFIX, subdirName));
            }
        }

        System.out.println("\n---------------------------------------");
        System.out.println("Robust " + result.robustSol); // prints "Robust SolveOutcome{...}"
        // evaluate robust vs. point policy in true game
        System.out.println("Evaluating robust strategy in true CSG...");
        verifyInTrueGame(result.robustSol, trueSol, exportStrat, modelFilePath, propertyIndex, robustnessExperiment, ROBUST_SUFFIX, subdirName);

        System.out.println("\n---------------------------------------");
        System.out.println("Point " + result.pointSol);
        System.out.println("Evaluating point strategy in true CSG...");
        verifyInTrueGame(result.pointSol, trueSol, exportStrat, modelFilePath, propertyIndex, robustnessExperiment, POINT_SUFFIX, subdirName);


    }

    /** Flat per-run summary for machine-readable batch results (one CSV row per run). */
    public static final class RunSummary {
        public int episodes;
        public double deltaT;
        public long nMin;
        public long totalSamples;      // number of sampled trajectories
        public long totalTransitions;  // number of individual environment transitions
        public double runtimeMs;
        public boolean trueFound;
        public double trueValue;
        public boolean robustFound;
        public double robustValue;
        public double robustTrueValue;
        public double robustValueGap;
        public boolean pointFound;
        public double pointValue;
        public double pointTrueValue;
        public double pointValueGap;
    }

    /** Build a machine-readable summary of a finished run (also solves the true game as oracle). */
    public RunSummary summarise(long durationNs, PacResult result) throws PrismException {
        RunSummary rs = new RunSummary();
        rs.episodes = result.episodes;
        rs.deltaT = result.deltaT;
        rs.nMin = this.nMin;
        rs.totalSamples = this.totalNumSamples;
        rs.totalTransitions = this.totalNumTransitions;
        rs.runtimeMs = durationNs / 1e6;

        SolveOutcome trueSol = solveTrueGame(helper.spec.propertiesFile, helper.spec.property);
        rs.trueFound = trueSol != null && trueSol.foundNE();
        rs.trueValue = trueSol != null ? trueSol.getValue() : Double.NaN;

        rs.robustFound = result.robustSol != null && result.robustSol.foundNE();
        rs.robustValue = result.robustSol != null ? result.robustSol.getValue() : Double.NaN;
        double[] robustEval = evalStrategyInTrueGame(result.robustSol, trueSol);
        rs.robustTrueValue = robustEval[0];
        rs.robustValueGap = robustEval[1];

        rs.pointFound = result.pointSol != null && result.pointSol.foundNE();
        rs.pointValue = result.pointSol != null ? result.pointSol.getValue() : Double.NaN;
        double[] pointEval = evalStrategyInTrueGame(result.pointSol, trueSol);
        rs.pointTrueValue = pointEval[0];
        rs.pointValueGap = pointEval[1];

        return rs;
    }

    /** Returns {value of sol's strategy in the true game, value gap vs. oracle}, NaNs if unavailable. */
    private double[] evalStrategyInTrueGame(SolveOutcome sol, SolveOutcome trueSol) {
        if (sol == null || !sol.foundNE() || sol.getStrategy() == null || trueSol == null) {
            return new double[]{Double.NaN, Double.NaN};
        }
        try {
            double v, gap;
            if (trueSol.getStrategy() != null && trueSol.getStrategy().sameChoices(sol.getStrategy())) {
                v = trueSol.getValue();
                gap = 0.0;
            } else {
                v = helper.computeValueInCSG(prism, trueGame, sol.getStrategy());
                gap = trueSol.getValue() - v;
            }
            return new double[]{v, gap};
        } catch (Exception e) {
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    private File getExportStrategyFile(String modelFilePath, int propertyIndex, boolean robustnessExperiment, String suffix, String subdirName) throws Exception {
        String root = Paths.get("").toAbsolutePath().toString() + "/prism-examples/csgs/learning/";
        Path rootDir = Paths.get(root);
        Path modelPath = Paths.get(modelFilePath);
        String filename = modelPath.getFileName().toString().replaceFirst("\\.[^.]+$", "") + propertyIndex + suffix; // remove extension

        // Build all strats directory path
        Path stratsDir = rootDir.resolve("strats");
        // Ensure strats directory exists
        Files.createDirectories(stratsDir);
        Path experimentDir = stratsDir.resolve(subdirName);
        Files.createDirectories(experimentDir);
        return experimentDir.resolve(filename).toFile();
    }


    public static void main(String[] args) throws Exception {
        Prism prism = new Prism();
        prism.initialise();
        prism.useNative();

        Experiment ex = new Experiment(Experiment.CaseStudy.SAFE_RISKY);
        ex.setSolverString("Yices");
        ex.propertyIndex = 4;
        ex.robustnessExperiment = false;
        ex.maxNumSamples = 10000;
        ex.epsilon = 0.2;
        Experiment.PacRunSpec spec = ex.buildPacRunSpec(prism);

        PACLearner learner = new PACLearner(prism, 88, true);
        String logSubdir = "eps";
        long start = System.nanoTime();
        PacResult res = learner.runPacLoop(spec, ex.modelFile, ex.propertyIndex, ex.robustnessExperiment, logSubdir);
        long end = System.nanoTime();
        long duration = end - start;

        learner.printResult(duration, res, true, ex.modelFile, ex.propertyIndex, ex.robustnessExperiment, logSubdir);
    }
}