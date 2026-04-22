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
import java.util.function.Function;

public class PACLearner {

    public static final double STOP_THRESH_FACTOR = 4.0;
    private static final double TRANS_PROB_LB = 1e-6;

    public static final class PacResult {
        public final boolean certificateNoExactNE;
        public final boolean terminatedByAllKnown;
        public final int episodes;
        public final double robustValue;
        public final double deltaT;
        public final CSGStrategy<Double> robustStrategy;

        public PacResult(boolean certificateNoExactNE,
                         boolean terminatedByAllKnown,
                         int episodes,
                         double robustValue,
                         double deltaT,
                         CSGStrategy<Double> robustStrategy) {
            this.certificateNoExactNE = certificateNoExactNE;
            this.terminatedByAllKnown = terminatedByAllKnown;
            this.episodes = episodes;
            this.robustValue = robustValue;
            this.deltaT = deltaT;
            this.robustStrategy = robustStrategy;
        }
    }

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

    private final Prism prism;

    private final List<List<Long>> slotCounts = new ArrayList<>();
    private final List<List<Map<Integer, Long>>> transitionCounts = new ArrayList<>();
    private final List<List<Boolean>> known = new ArrayList<>();

    private CSGSimple<Double> trueGame;
    private L1CSGSimple<Double> empiricalGame;
    private L1MDPSimple<Double> explorationRMDP;
    private long nMin;
    private double maxRadius = 0.0;
    private MDPRewardsSimple<Double> explorationRewards;
    private UCSGModelChecker empiricalMC;
    private Strategy<Double> explorationStrat;
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
                spec.eps,
                spec.delta,
                spec.rMax,
                spec.horizon
        );
    }

    public PacResult runPacLoop(
            CSGSimple<Double> trueGame,
            PropertiesFile propertiesFile,
            Property property,
            double eps,
            double delta,
            double rMax,
            int horizon
    ) throws PrismException {

        checkAndSetInput(trueGame, eps, delta, horizon);

        final double deltaContain = delta / 2.0;
        int episode = 0;
        double lastValue = Double.NaN;
        double lastDeltaT = 0.0;

        computeNmin(deltaContain, rMax, eps);
        initialiseRun(trueGame);

        while (true) {
            episode++;

            updateL1Transitions(deltaContain);

            double deltaT = computeDeltaT(rMax, horizon);
            SolveOutcome robustSol = robustSolveL1CSG(propertiesFile, property);

            boolean allKnown = allSlotsKnown();

            if (!robustSol.found && deltaT <= eps / STOP_THRESH_FACTOR) {
                return new PacResult(true, false, episode, robustSol.value, deltaT, robustSol.strategy);
            }

            if (robustSol.found && (deltaT <= eps / STOP_THRESH_FACTOR || allKnown)) {
                return new PacResult(false, allKnown, episode, robustSol.value, deltaT, robustSol.strategy);
            }

            explorationRMDP = new L1MDPSimple<>(empiricalGame);
            updateExplorationRewards();
            explorationStrat = solveExplorationRMDP();

            sampleTrajectory();
            updateKnown();

            if (deltaT != lastDeltaT) {
                printEpisodeResult(episode, robustSol, deltaT, allKnown);
            }
            lastValue = robustSol.value;
            lastDeltaT = deltaT;
        }
    }

    private void printEpisodeResult(int episode, SolveOutcome robustSol, double deltaT, boolean allKnown) {
        System.out.println();
        System.out.println("Episode " + episode + ":");
        System.out.println("    " + robustSol);
        System.out.println("    deltaT=" + deltaT + ", allKnown=" + allKnown);
        System.out.println("\n---------------------------------------");
    }

    private void sampleTrajectory() throws PrismException
    {
        prism.loadModelIntoSimulator();
        SimulatorEngine sim = prism.getSimulator();

        if (!(explorationStrat instanceof StrategyGenerator)) {
            throw new PrismException("Exploration strategy must be a StrategyGenerator for the simulator");
        }
        sim.loadStrategy((StrategyGenerator<Double>) explorationStrat);
        sim.setStrategyEnforced(true);

        sim.createNewPath();
        sim.initialisePath(sim.getModel().getInitialState());

        Function<State, Integer> stateIndexOf = state -> trueGame.getStatesList().indexOf(state);

        BitSet[] targets = empiricalMC.getTargets();
        int numCoalitions = targets.length;
        BitSet reached = new BitSet(numCoalitions);

        for (int h = 0; h < horizon; h++) {
            if (sim.queryIsDeadlock()) {
                break;
            }

            State before = sim.getCurrentState();
            int s = stateIndexOf.apply(before);
            if (s < 0) {
                throw new PrismException("Current state not found in explicit state list: " + before);
            }

            if (!sim.automaticTransition()) {
                break;
            }

            State after = sim.getCurrentState();
            int sp = stateIndexOf.apply(after);
            if (sp < 0) {
                throw new PrismException("Next state not found in explicit state list: " + after);
            }

            int choiceIndex = sim.getLastChoiceIndex();
            updateCount(s, choiceIndex, sp);

            for (int p = 0; p < numCoalitions; p++) {
                if (targets[p] != null && targets[p].get(sp)) {
                    reached.set(p);
                }
            }

            if (reached.cardinality() == numCoalitions) {
                break;
            }
        }
    }

    private void updateCount(int s, int c, int succ) throws PrismException {
        if (s < 0 || s >= slotCounts.size()) {
            throw new PrismException("State index out of range in updateCount: " + s);
        }
        if (c < 0 || c >= slotCounts.get(s).size()) {
            throw new PrismException("Choice index out of range in updateCount: " + c + " at state " + s);
        }

        slotCounts.get(s).set(c, slotCounts.get(s).get(c) + 1L);

        Map<Integer, Long> counts = transitionCounts.get(s).get(c);
        counts.put(succ, counts.getOrDefault(succ, 0L) + 1L);
    }

    private void checkAndSetInput(CSGSimple<Double> trueGame, double epsilon, double delta, int horizon) throws PrismException {
        if (trueGame == null) {
            throw new PrismException("trueGame is null");
        } else {
            this.trueGame = trueGame;
        }
        if (horizon <= 0) {
            throw new PrismException("horizon must be positive");
        } else {
            this.horizon = Math.min(horizon, Integer.MAX_VALUE);
        }
        if (epsilon <= 0.0) {
            throw new PrismException("epsilon must be positive");
        }
        if (delta <= 0.0 || delta >= 1.0) {
            throw new PrismException("delta must be in (0,1)");
        }
    }

    private void computeNmin(double deltaContain, double rMax, double eps) {
        int numStates = trueGame.getNumStates();
        int numChoices = trueGame.getActions().size();
        double c = -16.0 * rMax * rMax * Math.pow(horizon, 4.0) / (eps * eps);
        double inner = Math.sqrt(deltaContain / (2.0 * (Math.pow(2, numStates) - 2.0) * numStates * numChoices)) / c;
        double n = c * lambertWm1(inner);
        nMin = Math.max(1L, Math.round(n));
    }

    private double lambertWm1(double x) {
        if (Double.isNaN(x)) return Double.NaN;

        final double MIN_X = -1.0 / Math.E;

        if (x < MIN_X) {
            throw new IllegalArgumentException("W_{-1}(x) is undefined for x < -1/e.");
        }

        // W_{-1}(0) = -infinity as a limit, but not a finite value.
        if (x == 0.0) {
            return Double.NEGATIVE_INFINITY;
        }

        if (x == MIN_X) {
            return -1.0;
        }

        if (x > 0.0) {
            throw new IllegalArgumentException("W_{-1}(x) is real only for -1/e <= x < 0.");
        }

        // Initial guess
        double w;
        if (x < -0.3) {
            // Near the branch point x = -1/e:
            // W_{-1}(x) = -1 - p - p^2/3 - 11 p^3/72 - 43 p^4/540 - ...
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
            // As x -> 0-, W_{-1}(x) ~ ln(-x) - ln(-ln(-x))
            double L1 = Math.log(-x);
            double L2 = Math.log(-L1);
            w = L1 - L2 + L2 / L1;
        }

        // Halley's method
        for (int i = 0; i < 30; i++) {
            double ew = Math.exp(w);
            double f = w * ew - x;

            if (Math.abs(f) <= 1e-16 * (1.0 + Math.abs(x))) {
                return w;
            }

            double wp1 = w + 1.0;
            double fp = ew * wp1;

            // Halley step with a safe fallback near w = -1
            double step;
            if (Math.abs(wp1) < 1e-8 || !Double.isFinite(fp) || fp == 0.0) {
                step = f / fp; // Newton fallback
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

    private void initialiseRun(CSGSimple<Double> template) {
        int numStates = template.getNumStates();
        List<List<Distribution<Double>>> trans = new ArrayList<>();

        slotCounts.clear();
        transitionCounts.clear();
        known.clear();

        for (int s = 0; s < numStates; s++) {
            int numChoices = template.getNumChoices(s);

            known.add(new ArrayList<>(numChoices));
            trans.add(new ArrayList<>(numChoices));
            slotCounts.add(new ArrayList<>(numChoices));
            transitionCounts.add(new ArrayList<>(numChoices));

            for (int c = 0; c < numChoices; c++) {
                known.get(s).add(false);
                slotCounts.get(s).add(0L);
                transitionCounts.get(s).add(new HashMap<>());

                Iterator<Integer> successors = template.getSuccessorsIterator(s, c);
                List<Integer> succs = new ArrayList<>();
                while (successors.hasNext()) {
                    int succ = successors.next();
                    succs.add(succ);
                    transitionCounts.get(s).get(c).put(succ, 0L);
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

        empiricalGame = new L1CSGSimple<>(template, trans);
        explorationRMDP = new L1MDPSimple<>(empiricalGame);
        explorationRewards = new MDPRewardsSimple<>(explorationRMDP.getNumStates());
    }

    private void updateL1Transitions(double deltaContain) {
        maxRadius = 0.0; // reset every episode
        int numStates = empiricalGame.getNumStates();
        for (int s = 0; s < numStates; s++) {
            for (int c = 0; c < empiricalGame.getNumChoices(s); c++) {
                long saCount = slotCounts.get(s).get(c);
                double radius;
                if (saCount == 0L) {
                    maxRadius = L1CSGSimple.INIT_RADIUS; // no need to look at other slots, since this one has the maximum radius
                    continue;
                }
                double deltaSlot = deltaContain / (empiricalGame.getNumChoices() * saCount * (saCount + 1.0));
                radius = weissmanRadius(saCount, deltaSlot);

                if (radius > maxRadius) {
                    maxRadius = radius;
                }

                empiricalGame.setRadius(s, c, radius);

                int sFinal = s;
                int cFinal = c;

                empiricalGame.getSuccessorsIterator(s, c).forEachRemaining(succ -> {
                    double pHat = transitionCounts.get(sFinal).get(cFinal).getOrDefault(succ, 0L) / (double) saCount;
                    pHat = Math.max(TRANS_PROB_LB, pHat);
                    empiricalGame.setCentre(sFinal, cFinal, succ, pHat);
                });
            }
        }

        if (maxRadius < L1CSGSimple.INIT_RADIUS) {
            System.out.println("New max radius: " + maxRadius);
        }
    }

    private double weissmanRadius(double saCount, double deltaSlot) {
        if (Double.isNaN(saCount) || Double.isNaN(deltaSlot)) {
            return Double.NaN;
        }
        if (saCount <= 0.0) {
            throw new IllegalArgumentException("saCount must be > 0");
        }
        if (!(deltaSlot > 0.0) || deltaSlot >= 1.0) {
            throw new IllegalArgumentException("deltaSlot must be in (0, 1)");
        }

        // Weissman radius:
        // alpha(n; delta) = sqrt( (2 / n) * ln((2^|S| - 2) / delta) )
        double r = Math.sqrt((2.0 / saCount) * (empiricalGame.getNumStates() * Math.log(2.0) - Math.log(deltaSlot)));
        return Math.min(r, L1CSGSimple.INIT_RADIUS);
    }

    private double computeDeltaT(double rMax, int horizon) {
        return 0.5 * rMax * horizon * horizon * maxRadius;
    }

    private void updateExplorationRewards() {
        for (int s = 0; s < explorationRMDP.getNumStates(); s++) {
            for (int c = 0; c < explorationRMDP.getNumChoices(s); c++) {
                explorationRewards.setTransitionReward(s, c, known.get(s).get(c) ? 0.0 : 1.0);
            }
        }
    }

    private SolveOutcome robustSolveL1CSG(PropertiesFile propertiesFile, Property property) throws PrismException {
        prism.getSettings().set(PrismSettings.PRISM_SMT_SOLVER, "Yices");
        StateModelChecker mc = explicit.StateModelChecker.createModelChecker(empiricalGame.getModelType(), prism);
        if (!(mc instanceof UCSGModelChecker)) {
            throw new PrismException("Expected a UCSGModelChecker for robust solving, but got " + mc.getClass().getSimpleName());
        }
        empiricalMC = (UCSGModelChecker) mc;
        empiricalMC.setModelCheckingInfo(prism.getModelInfo(), propertiesFile, prism.getRewardGenerator());
        empiricalMC.setGenStrat(true);
        empiricalMC.setSilentPrecomputations(true);
        // defaults
//        empiricalMC.setPrecomp(true);
//        empiricalMC.setPrecomp(true);
//        empiricalMC.setVerbosity(0);

        try {
            StateValues sv = empiricalMC.checkExpression(empiricalGame, property.getExpression(), null, true);
            if (sv == null) {
                return new SolveOutcome(false, null, Double.NaN);
            }

            double[] vals = sv.getDoubleArray();
            if (vals == null) {
                return new SolveOutcome(false, null, Double.NaN);
            }

            int init = empiricalGame.getFirstInitialState();
            if (init < 0 || init >= vals.length) {
                throw new PrismException("Initial state index out of range for robust solve.");
            }

            double value = vals[init]; // social-welfare value
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return new SolveOutcome(false, null, value);
            }

            CSGStrategy<Double> strategy = null;
            Strategy<?> strat = empiricalMC.getStrategy();
            if (strat instanceof CSGStrategy) {
                strategy = (CSGStrategy<Double>) strat;
                return new SolveOutcome(true, strategy, value);
            } else if (strat == null) {
                throw new PrismException("Expected a CSGStrategy from robust solve, but got null");
            } else {
                throw new PrismException("Expected a CSGStrategy from robust solve, but got null" + strat.getClass().getSimpleName());
            }
        } catch (PrismException e) {
            return new SolveOutcome(false, null, Double.NaN);
        }
    }

    private Strategy<Double> solveExplorationRMDP() throws PrismException {
        UMDPModelChecker mc = new UMDPModelChecker(this.prism);
        mc.setGenStrat(true);
        mc.setPrecomp(true);
        mc.setSilentPrecomputations(true);
        mc.setVerbosity(0);
        ModelCheckerResult res = mc.computeCumulativeRewards(explorationRMDP, explorationRewards, horizon, MinMax.max().setMinUnc(true));
        if (res == null || res.strat == null) {
            throw new PrismException("Exploration solver did not return a strategy.");
        }
        return (Strategy<Double>) res.strat;
    }

    private void updateKnown() {
        for (int s = 0; s < empiricalGame.getNumStates(); s++) {
            for (int c = 0; c < empiricalGame.getNumChoices(s); c++) {
                boolean newKnown = slotCounts.get(s).get(c) >= nMin;
//                if (newKnown)
//                    System.out.println("new known slot: " + "(s=" + s + ", c=" + c + ") with count " + slotCounts.get(s).get(c));
                known.get(s).set(c, newKnown);
            }
        }
    }

    private boolean allSlotsKnown() {
        for (int s = 0; s < empiricalGame.getNumStates(); s++) {
            for (int c = 0; c < empiricalGame.getNumChoices(s); c++) {
                if (slotCounts.get(s).get(c) < nMin) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        Prism prism = new Prism();
        prism.initialise();
        prism.useNative();

        Experiment ex = new Experiment(Experiment.Model.TINY_ALOHA);
        ex.setSolverString("yices");

        Experiment.PacRunSpec spec = ex.buildPacRunSpec(prism);

        PACLearner learner = new PACLearner(prism, 41);
        PacResult res = learner.runPacLoop(spec);

        System.out.println("certificateNoExactNE=" + res.certificateNoExactNE);
        System.out.println("terminatedByAllKnown=" + res.terminatedByAllKnown);
        System.out.println("episodes=" + res.episodes);
        System.out.println("robustValue=" + res.robustValue);
        System.out.println("deltaT=" + res.deltaT);
        System.out.println("hasStrategy=" + (res.robustStrategy != null));
    }
}
