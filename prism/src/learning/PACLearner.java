package learning;

import explicit.*;
import explicit.rewards.MDPRewardsSimple;
import parser.State;
import parser.ast.Property;
import parser.ast.PropertiesFile;
import prism.*;
import simulator.SimulatorEngine;
import strat.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class PACLearner {

    public static final double NASH_STOP_THRESH = 4.0;
    public static final double ZERO_SUM_STOP_THRESH = 2.0;
    private static final double TRANS_PROB_LB = 1e-6;
    private static final String DEFAULT_SMT_SOLVER = "Yices";
    private static final double RADIUS_CHANGE_THRESH = 0.05;

    private static final class SolveOutcome {
        final boolean found;
        final CSGStrategy<Double> strategy;
        final double value;

        SolveOutcome(boolean found, CSGStrategy<Double> strategy, double value) {
            this.found = found;
            this.strategy = strategy;
            this.value = value;
        }

        @Override
        public String toString() {
            return "SolveOutcome{" +
                    "found=" + found +
                    ", value=" + value +
                    '}';
        }
    }

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

    private Map<State, Integer> stateToIndex;
    BitSet[] targets;
    SimulatorEngine sim;
    private int episode;

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
//        System.out.println("Using effective horizon: " + spec.horizon);
        return runPacLoop(
                spec.trueGame,
                spec.propertiesFile,
                spec.property,
                spec.epsilon,
                spec.confidence,
                spec.rMax,
                spec.horizon,
                spec.solverString,
                spec.zeroSum
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
            boolean zeroSum
    ) throws PrismException {

        final double deltaContain = confidence / 2.0;
        final double deltaCov= confidence / 2.0;
        episode = 1;
        this.horizon = horizon;
        this.trueGame = trueGame;
        initialiseRun(trueGame, solver, propertiesFile, property, zeroSum);
        double stopThreshFactor = zeroSum ? ZERO_SUM_STOP_THRESH : NASH_STOP_THRESH;
        computeNmin(deltaContain, rMax, eps, stopThreshFactor);
        double stopThresh = eps / stopThreshFactor;
        double deltaT;

        while (true) {
            deltaT = computeDeltaT(rMax, horizon);
            if (deltaT <= stopThresh) {
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

            int numSamples = computeNumSamples(deltaCov);
            System.out.println("Episode " + episode + ": Sampling " + numSamples + " trajectories with current exploration strategy...");
            for (int i = 0; i < numSamples; i++)
                sampleTrajectory();

            update(deltaContain);
//            printEpisodeResult(episode, deltaT);
            episode++;
        }
    }

    private int computeNumSamples(double deltaCov) {
        double deltaEpisode = deltaCov / (episode * (episode + 1.0));
        return (int) Math.ceil(- Math.log(deltaEpisode) * Math.max(1, numUnknownSlots));
    }

    private void printEpisodeResult(int episode, double deltaT) {
        System.out.println("Episode " + episode + ":  deltaT=" + deltaT);
        System.out.println("---------------------------------------");
    }

    private void sampleTrajectory() throws PrismException {
        sim.createNewPath();
        sim.initialisePath(sim.getModel().getInitialState());

        int numCoalitions = targets.length;
        BitSet reached = new BitSet(numCoalitions);
        for (int h = 0; h < horizon; h++) {
            if (sim.queryIsDeadlock()) break;

            State before = sim.getCurrentState();
            Integer s = stateToIndex.get(before);
            if (s == null) {
                throw new PrismException("Current state not found in explicit state list: " + before);
            }

            if (!sim.automaticTransition()) break;

            State after = sim.getCurrentState();
            Integer sp = stateToIndex.get(after);
            if (sp == null) {
                throw new PrismException("Next state not found in explicit state list: " + after);
            }

            int choiceIndex = sim.getLastChoiceIndex();
            updateCount(s, choiceIndex, sp);

            for (int p = 0; p < numCoalitions; p++) {
                if (targets[p] != null && targets[p].get(sp)) {
                    reached.set(p);
                }
            }
        }
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
        double n = c * lambertWm1(inner);
        nMin = Math.max(1L, Math.round(n));
    }

    private double lambertWm1(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        final double MIN_X = -1.0 / Math.E;

        if (x < MIN_X)  throw new IllegalArgumentException("W_{-1}(x) is undefined for x < -1/e.");
        if (x == 0.0)  return Double.NEGATIVE_INFINITY;
        if (x == MIN_X) return -1.0;
        if (x > 0.0)  throw new IllegalArgumentException("W_{-1}(x) is real only for -1/e <= x < 0.");

        double w;
        if (x < -0.3) {
            double p = Math.sqrt(2.0 * (Math.E * x + 1.0));
            double p2 = p * p;
            double p3 = p2 * p;
            double p4 = p2 * p2;
            double p5 = p4 * p;
            w = -1.0
                    - p
                    - p2 / 3.0
                    - 11.0 * p3 / 72.0
                    - 43.0 * p4 / 540.0
                    - 769.0 * p5 / 17280.0;
        } else {
            double L1 = Math.log(-x);
            double L2 = Math.log(-L1);
            w = L1 - L2 + L2 / L1;
        }

        for (int i = 0; i < 30; i++) {
            double ew = Math.exp(w);
            double f = w * ew - x;

            if (Math.abs(f) <= 1e-16 * (1.0 + Math.abs(x))) {
                return w;
            }

            double wp1 = w + 1.0;
            double fp = ew * wp1;

            double step;
            if (Math.abs(wp1) < 1e-8 || !Double.isFinite(fp) || fp == 0.0) {
                step = f / fp;
            } else {
                double denom = fp - 0.5 * f * (w + 2.0) / wp1;
                step = f / denom;
            }

            double wNext = w - step;

            if (wNext == w || Math.abs(wNext - w) <= 1e-15 * (1.0 + Math.abs(wNext))) {
                return wNext;
            }

            w = wNext;
        }

        return w;
    }

    private void initialiseRun(CSGSimple<Double> template, String solver, PropertiesFile propertiesFile, Property property, boolean zeroSum) throws PrismException {
        int numStates = template.getNumStates();
        List<List<Distribution<Double>>> trans = new ArrayList<>();

        slotCounts = new long[numStates][];
        transitionCounts = new long[numStates][][];
        known = new boolean[numStates][];
        numUnknownSlots = 0;

        for (int s = 0; s < numStates; s++) {
            int numChoices = template.getNumChoices(s);

            known[s] = new boolean[numChoices];
            numUnknownSlots += numChoices;
            trans.add(new ArrayList<>(numChoices));
            slotCounts[s] = new long[numChoices];
            transitionCounts[s] = new long[numChoices][];

            for (int c = 0; c < numChoices; c++) {
                transitionCounts[s][c] = new long[numStates];

                Iterator<Integer> successors = template.getSuccessorsIterator(s, c);
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

        empiricalGame = new L1CSGSimple<>(template, trans);
        explorationRMDP = new L1MDPSimple<>(empiricalGame);
        explorationRewards = new MDPRewardsSimple<>(explorationRMDP.getNumStates());
        String chosenSolver = (solver == null || solver.isBlank()) ? DEFAULT_SMT_SOLVER : solver;
        prism.getSettings().set(PrismSettings.PRISM_SMT_SOLVER, chosenSolver);

        stateToIndex = new HashMap<>();
        for (int i = 0; i < trueGame.getStatesList().size(); i++) {
            stateToIndex.put(trueGame.getStatesList().get(i), i);
        }

        initialiseMC(propertiesFile, property, zeroSum);
        prism.loadModelIntoSimulator();
        sim = prism.getSimulator();
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
                double radius = weissmanRadius(saCount, deltaSlot);
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

    private double weissmanRadius(double saCount, double deltaSlot) {
        double r = Math.sqrt((2.0 / saCount) * (empiricalGame.getNumStates() * Math.log(2.0) - Math.log(deltaSlot)));
        return Math.min(r, 2.0);
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

        robustSolveL1CSG(property); // solve once so that the model checker records the target states
        targets = zeroSum ? new BitSet[]{empiricalMC.getTarget()} : empiricalMC.getTargets();
    }

    private SolveOutcome robustSolveL1CSG(Property property) {
        return getSolveOutcome(empiricalMC, empiricalGame, property);
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

        return getSolveOutcome(trueMC, trueGame, property);
    }

    private SolveOutcome getSolveOutcome(ProbModelChecker mc, CSGSimple<Double> game, Property property) {
        try {
            StateValues sv = mc.checkExpression(game, property.getExpression(), null, true);
            if (sv == null) {
                System.err.println("Warning: solve did not return state values.");
                return new SolveOutcome(false, null, Double.NaN);
            }

            double[] vals = sv.getDoubleArray();
            if (vals == null) {
                System.err.println("Warning: robust solve did not return double values.");
                return new SolveOutcome(false, null, Double.NaN);
            }

            int init = game.getFirstInitialState();
            if (init < 0 || init >= vals.length) {
                throw new PrismException("Initial state index out of range for robust solve.");
            }

            double value = vals[init];
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                System.err.println("Warning: robust solve returned invalid value: " + value);
                return new SolveOutcome(false, null, value);
            }

            Strategy<?> strat = mc.getStrategy();
            if (strat instanceof CSGStrategy<?> csgStrat) {
                @SuppressWarnings("unchecked")
                CSGStrategy<Double> strategy = (CSGStrategy<Double>) csgStrat;
                return new SolveOutcome(true, strategy, value);
            } else if (strat == null) {
                return new SolveOutcome(true, null, value);
            } else {
                throw new PrismException("Expected a CSGStrategy from solve, but got " + strat.getClass().getSimpleName());
            }
        } catch (PrismException e) {
            return new SolveOutcome(false, null, Double.NaN);
        }
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
        sim.loadStrategy((StrategyGenerator<Double>) explorationStrat);
        sim.setStrategyEnforced(true);
    }


    public static void main(String[] args) throws Exception {
        Prism prism = new Prism();
        prism.initialise();
        prism.useNative();

        Experiment ex = new Experiment(Experiment.CASE_STUDY.TINY_ALOHA);
        ex.setSolverString("Yices");

        Experiment.PacRunSpec spec = ex.buildPacRunSpec(prism);

        PACLearner learner = new PACLearner(prism, 41);
        long start = System.nanoTime();
        PacResult res = learner.runPacLoop(spec);
        long end = System.nanoTime();
        long duration = end - start;

        System.out.println("Execution time: " + duration  / 1e6 + " milliseconds");

        System.out.println("noExactNE=" + res.noExactNE);
        System.out.println("episodes=" + res.episodes);
        System.out.println("robustValue=" + res.robustValue);
        System.out.println("deltaT=" + res.deltaT);
        System.out.println("nMin=" + learner.nMin);

        System.out.println("---------------------------------------");
        SolveOutcome trueSol = learner.solveTrueGame(spec.propertiesFile, spec.property);
        System.out.println("True value: " + trueSol);
    }
}