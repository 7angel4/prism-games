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
    private BitSet explorationTarget;
    private UCSGModelChecker empiricalMC;
    Strategy<Double> explorationStrat;


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

        checkValidInput(trueGame, eps, delta, horizon);

        final double deltaContain = delta / 2.0;
        int episode = 0;
        this.trueGame = trueGame;

        computeNmin(trueGame.getNumStates(), trueGame.getActions().size(), deltaContain, rMax, horizon, eps);
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
            explorationStrat = solveExplorationRMDP(horizon);

            sampleTrajectory(horizon);
            updateKnown();

            System.out.println();
            System.out.println("Episode " + episode + ":");
            System.out.println("    " + robustSol);
            System.out.println("    deltaT=" + deltaT + ", allKnown=" + allKnown);
            System.out.println("\n---------------------------------------");
        }
    }

    private void sampleTrajectory(int horizon) throws PrismException
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

    private void checkValidInput(CSGSimple<Double> trueGame,
                                 double epsilon,
                                 double delta,
                                 int horizon) throws PrismException {
        if (trueGame == null) {
            throw new PrismException("trueGame is null");
        }
        if (horizon <= 0) {
            throw new PrismException("horizon must be positive");
        }
        if (epsilon <= 0.0) {
            throw new PrismException("epsilon must be positive");
        }
        if (delta <= 0.0 || delta >= 1.0) {
            throw new PrismException("delta must be in (0,1)");
        }
    }

    private void computeNmin(int numStates, int numChoices, double deltaContain, double rMax, int horizon, double eps) {
        double c = -16.0 * rMax * rMax * Math.pow(horizon, 4.0) / (eps * eps);
        double inner = Math.sqrt(deltaContain / ((Math.pow(2, numStates) - 2.0) * numStates * numChoices)) / c;
        double n = c * lambertW(inner);
        nMin = Math.max(1L, Math.round(n));
    }

    private double lambertW(double x) {
        if (x <= 0.0) {
            return 0.0;
        }
        return Math.log(x) - Math.log(Math.log(x));
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
        explorationTarget = new BitSet(empiricalGame.getNumStates());
    }

    private void updateL1Transitions(double deltaContain) throws PrismException {
        int numStates = empiricalGame.getNumStates();
        for (int s = 0; s < numStates; s++) {
            for (int c = 0; c < empiricalGame.getNumChoices(s); c++) {
                long saCount = slotCounts.get(s).get(c);
                if (saCount == 0L) {
                    maxRadius = L1CSGSimple.INIT_RADIUS;
                    continue;
                }

                double deltaSlot = deltaContain / (empiricalGame.getNumChoices() * saCount * (saCount + 1.0));
                double radius = weissmanRadius(saCount, deltaSlot);

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
    }

    private double weissmanRadius(double saCount, double deltaSlot) {
        return Math.sqrt((2.0 / saCount) * (empiricalGame.getNumStates() * Math.log(2.0) - Math.log(deltaSlot)));
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

    private Strategy<Double> solveExplorationRMDP(int horizon) throws PrismException {
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

        Experiment ex = new Experiment(Experiment.Model.TEST_CSG);
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
