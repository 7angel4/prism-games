package learning;

import explicit.*;
import explicit.rewards.CSGRewards;
import explicit.rewards.MDPRewardsSimple;
import parser.State;
import parser.ast.Property;
import prism.Prism;
import prism.PrismException;
import simulator.SimulatorEngine;
import strat.CSGStrategy;
import strat.InvalidStrategyStateException;
import strat.Strategy;

import java.util.*;

public class PACHelper {
    protected Map<State, Integer> stateToIndex;
    protected BitSet[] targets;
    protected List<CSGRewards<Double>> rewards;
    protected SimulatorEngine sim;
    protected Experiment.PacRunSpec spec;

    public static final class SolveOutcome {
        private final boolean found;
        private final CSGStrategy<Double> strategy;
        private final double value;

        public SolveOutcome(boolean found, CSGStrategy<Double> strategy, double value) {
            this.found = found;
            this.strategy = strategy;
            this.value = value;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{" +
                    "found = " + found +
                    ", value = " + value + ", " +
                    "strategy = " + strategy +
                    '}';
        }

        public boolean foundNE() {
            return found;
        }

        public CSGStrategy<Double> getStrategy() {
            return strategy;
        }

        public double getValue() {
            return value;
        }
    }

    @FunctionalInterface
    protected interface UpdateFn {
        void apply(int s, int c, int succ);
    }

    protected void sampleTrajectory(int horizon, UpdateFn update) throws PrismException {
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
            update.apply(s, choiceIndex, sp);

            for (int p = 0; p < numCoalitions; p++) {
                if (targets[p] != null && targets[p].get(sp)) {
                    reached.set(p);
                }
            }
        }
    }


    protected double weissmanRadius(L1CSG<Double> game, double saCount, double deltaSlot) {
        double r = Math.sqrt((2.0 / saCount) * (game.getNumStates() * Math.log(2.0) - Math.log(deltaSlot)));
        return Math.min(r, 2.0);
    }

    protected double lambertWm1(double x) {
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
            w = -1.0 - p - p2 / 3.0 - 11.0 * p3 / 72.0 - 43.0 * p4 / 540.0 - 769.0 * p5 / 17280.0;
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

    protected void setStateToIndex(CSG<Double> csg) {
        stateToIndex = new HashMap<>();
        for (int i = 0; i < csg.getStatesList().size(); i++) {
            stateToIndex.put(csg.getStatesList().get(i), i);
        }
    }

    protected SolveOutcome getSolveOutcome(ProbModelChecker mc, CSGSimple<Double> game, Property property) {
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
            if (Double.isNaN(value)) { // infinity is a possible valid value for e.g. unbounded reachability reward
                System.err.println("Warning: solve returned invalid value: " + value);
                return new SolveOutcome(false, null, value);
            }

            Strategy<?> strat = mc.getStrategy();
            if (strat instanceof CSGStrategy<?> csgStrat) {
                @SuppressWarnings("unchecked")
                CSGStrategy<Double> strategy = (CSGStrategy<Double>) csgStrat;
                return new SolveOutcome(true, strategy, value);
            } else if (strat == null) { // NE found but strategy generation may not be supported
                return new SolveOutcome(true, null, value);
            } else {
                throw new PrismException("Expected a CSGStrategy from solve, but got " + strat.getClass().getSimpleName());
            }
        } catch (PrismException e) {
            return new SolveOutcome(false, null, Double.NaN);
        }
    }

    protected double[] computeReachProbs(Prism prism, L1CSG<Double> game, BitSet target, MinMax minMax) throws PrismException {
        UMDPModelChecker mc = new UMDPModelChecker(prism);
        mc.setGenStrat(false); // we just want the reachability probabilities
        mc.setPrecomp(true);
        mc.setSilentPrecomputations(true);
        mc.setVerbosity(0);

        // the initial empirical game allows full simplex
        ModelCheckerResult res = mc.computeReachProbs(game, target, minMax.setMinUnc(true));

        if (res == null || res.soln == null) {
            throw new PrismException("Reachability solver did not return a value.");
        }
        return res.soln;
    }

    protected BitSet getTargetUnion() {
        BitSet target = new BitSet();
        for (BitSet t : targets) {
            if (t != null) target.or(t);
        }
        return target;
    }


    // STRATEGY EVALUATION
    public double computeValueInCSG(
            Prism prism,
            CSG<Double> csg,
            CSGStrategy<Double> strategy
    ) throws PrismException, InvalidStrategyStateException {

        // 1) Generate induced MDP from strategy
        MDPSimple<Double> mdp = strategy.generateMDP(csg);
        mdp.findDeadlocks(true);

        // 2) Construct target set (mapped from CSG → MDP)
        BitSet target = constructMDPTarget(mdp);

        // 3) Model check MDP directly
        MDPModelChecker mc = new MDPModelChecker(prism);
        mc.setSilentPrecomputations(true);

        ModelCheckerResult res;
        boolean min = spec.zeroSum ? !spec.minMax.isMin() : spec.minMax.isMinUnc();

        if (spec.useRewards) {
            MDPRewardsSimple<Double> rew = constructMDPRewards(mdp, csg);
            res = mc.computeReachRewards(mdp, rew, target, !spec.minMax.isMin());
        } else {
            res = mc.computeReachProbs(mdp, target, !spec.minMax.isMin());
        }

        if (res == null || res.soln == null) {
            throw new PrismException("MDP evaluation failed");
        }

        return res.soln[mdp.getFirstInitialState()];
    }

    protected BitSet constructMDPTarget(MDPSimple<Double> mdp) {

        BitSet csgTarget = getTargetUnion();
        BitSet mapped = new BitSet();

        List<State> states = mdp.getStatesList();

        for (int i = 0; i < states.size(); i++) {
            Integer orig = stateToIndex.get(states.get(i));
            if (orig != null && csgTarget.get(orig)) {
                mapped.set(i);
            }
        }

        return mapped;
    }

    protected MDPRewardsSimple<Double> constructMDPRewards(
            MDPSimple<Double> mdp,
            CSG<Double> csg
    ) {
        MDPRewardsSimple<Double> rew = new MDPRewardsSimple<>(mdp.getNumStates());

        List<State> mdpStates = mdp.getStatesList();

        for (int s = 0; s < mdp.getNumStates(); s++) {

            Integer orig = stateToIndex.get(mdpStates.get(s));
            if (orig == null) continue;

            // ---- STATE REWARD ----
            double stateR = 0.0;
            if (rewards != null) {
                for (CSGRewards<Double> r : rewards) {
                    if (r != null) {
                        stateR += r.getStateReward(orig);
                    }
                }
            }
            rew.setStateReward(s, stateR);

            // ---- TRANSITION REWARD ----
            int numChoices = mdp.getNumChoices(s);

            for (int a = 0; a < numChoices; a++) {

                double expectedReward = 0.0;

                Distribution<Double> mdpDistr = mdp.getChoice(s, a);

                // reconstruct expectation over original CSG choices
                for (int c = 0; c < csg.getNumChoices(orig); c++) {

                    Distribution<?> csgDistr = csg.getChoice(orig, c);
                    if (csgDistr == null) continue;

                    // measure how much this CSG choice contributes to MDP choice
                    double weight = 0.0;

                    for (Map.Entry<Integer, ?> e : csgDistr) {
                        int succ = e.getKey();
                        double p = ((Number) e.getValue()).doubleValue();

                        if (mdpDistr.contains(succ)) {
                            weight += Math.min(p, mdpDistr.get(succ));
                        }
                    }

                    if (weight <= 1e-12) continue;

                    double rVal = 0.0;
                    if (rewards != null) {
                        for (CSGRewards<Double> r : rewards) {
                            if (r != null) {
                                rVal += r.getTransitionReward(orig, c);
                            }
                        }
                    }

                    expectedReward += weight * rVal;
                }

                rew.setTransitionReward(s, a, expectedReward);
            }
        }

        return rew;
    }

    // STRATEGY EXTRACTION
    List<Map<BitSet, Double>> extractNEStrategy(CSGStrategy<Double> strategy, int s) {
        List<Map<BitSet, Double>> result = new ArrayList<>();
        int numPlayers = spec.zeroSum ? 1 : PACLearner.NUM_COALITIONS;

        for (int p = 0; p < numPlayers; p++) {
            Map<BitSet, Double> dist = strategy.getChoiceDistribution(p, 0, s);
            if (dist == null) {
                throw new RuntimeException("Missing strategy for player " + p);
            }
            result.add(dist);
        }
        return result;
    }
}
