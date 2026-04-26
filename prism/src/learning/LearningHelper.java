package learning;

import explicit.*;
import explicit.rewards.CSGRewards;
import explicit.rewards.MDPRewardsSimple;
import param.Function;
import parser.State;
import parser.ast.Property;
import prism.Evaluator;
import prism.Prism;
import prism.PrismException;
import simulator.SimulatorEngine;
import strat.CSGStrategy;
import strat.InvalidStrategyStateException;
import strat.Strategy;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LearningHelper {
    protected Map<State, Integer> stateToIndex;
    protected BitSet[] targets;
    protected List<CSGRewards<Double>> rewards;
    protected SimulatorEngine sim;

    public static final class SolveOutcome {
        final boolean found;
        final CSGStrategy<Double> strategy;
        final double value;

        public SolveOutcome(boolean found, CSGStrategy<Double> strategy, double value) {
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

    private void printEpisodeResult(int episode, double deltaT) {
        System.out.println("Episode " + episode + ":  deltaT=" + deltaT);
        System.out.println("---------------------------------------");
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


    // For evaluation of the robust strategy output in the true game

    public double computeCSGValue(Prism prism, CSG<Double> csg, CSGStrategy<Double> strategy) throws PrismException, InvalidStrategyStateException {
        DTMCSimple<Double> dtmc = constructInducedDTMC(csg, strategy);
        dtmc.findDeadlocks(true);

        DTMCModelChecker mc = new DTMCModelChecker(prism);
        mc.setSilentPrecomputations(true);
        mc.setVerbosity(0);

        ModelCheckerResult res;
        BitSet dtmcTarget = constructDTMCTarget(dtmc);
        if (rewards != null) {
            MDPRewardsSimple<Double> dtmcRewards = constructDTMCRewards(dtmc, rewards);
            res = mc.computeReachRewards(dtmc, dtmcRewards, dtmcTarget);
        } else {
            res = mc.computeReachProbs(dtmc, dtmcTarget);
        }

        if (res == null || res.soln == null) {
            throw new PrismException("DTMC reachability failed");
        }

        int init = dtmc.getFirstInitialState();
        double value = res.soln[init];

        // Debug (optional but VERY useful)
        System.out.println("\n=== DTMC VALUES ===");
        for (int s = 0; s < res.soln.length; s++) {
            System.out.println("state " + s + " -> " + res.soln[s]);
        }

        System.out.println("\nInitial state = " + init);
        System.out.println("True value = " + value);

        return value;
    }

    protected DTMCSimple<Double> constructInducedDTMC(CSG<Double> game, CSGStrategy<Double> strategy) throws PrismException, InvalidStrategyStateException {
        MDPSimple mdp = strategy.generateMDPEquilibria();
        System.out.println("Induced MDP = " + mdp);
        // convert to DTMC
        DTMCSimple<Double> dtmc = new DTMCSimple<>(mdp.getNumStates());
        dtmc.setEvaluator(Evaluator.forDouble());

        dtmc.setStatesList(mdp.getStatesList());
        dtmc.setConstantValues(game.getConstantValues());
        dtmc.setVarList(game.getVarList());

        for (Map.Entry<String, BitSet> entry : game.getLabelToStatesMap().entrySet()) {
            dtmc.addLabel(entry.getKey(), (BitSet) entry.getValue().clone());
        }

        dtmc.addInitialState(mdp.getFirstInitialState());

        for (int s = 0; s < mdp.getNumStates(); s++) {
            if (mdp.getNumChoices(s) == 0) continue;
            Distribution<Double> distr = mdp.getChoice(s, 0);
            for (Map.Entry<Integer, Double> e : distr) {
                dtmc.addToProbability(s, e.getKey(), e.getValue());
            }
        }
//        System.out.println("DTMC = " + dtmc);
        return dtmc;
    }


    protected BitSet constructDTMCTarget(DTMCSimple<Double> dtmc) {
        BitSet csgTarget = new BitSet();
        for (int p = 0; p < targets.length; p++) {
            csgTarget.or(targets[p]);
        }

        BitSet dtmcTarget = new BitSet(); //
        for (State s : dtmc.getStatesList()) {
            int dtmcStateIndex = stateToIndex.get(s);
            if (csgTarget.get(dtmcStateIndex)) continue; // already added from another player's dtmcTarget
            dtmcTarget.set(dtmcStateIndex);
        }
        return dtmcTarget;
    }

    protected MDPRewardsSimple<Double> constructDTMCRewards(DTMCSimple<Double> dtmc, List<CSGRewards<Double>> rewards)
    {
        MDPRewardsSimple<Double> dtmcRewards = new MDPRewardsSimple<>(dtmc.getNumStates());
        int numStates = dtmc.getNumStates();

        for (int s = 0; s < numStates; s++) {

            State state = dtmc.getStatesList().get(s);
            int orig = stateToIndex.get(state);  // map back to CSG state
            // State reward aggregation
            double stateRew = 0.0;
            if (rewards != null) {
                for (CSGRewards<Double> r : rewards) {
                    if (r != null) {
                        stateRew += r.getStateReward(orig);
                    }
                }
            }
            dtmcRewards.setStateReward(s, stateRew);

            // Transition reward (DTMC = 1 choice)
            if (dtmc.getNumTransitions(s) > 0) {
                double transRew = 0.0;
                if (rewards != null) {
                    for (CSGRewards<Double> r : rewards) {
                        if (r != null) {
                            // use choice 0 (DTMC collapsed MDP)
                            transRew += r.getTransitionReward(orig, 0);
                        }
                    }
                }
                dtmcRewards.setTransitionReward(s, 0, transRew);
            }
        }
        return dtmcRewards;
    }


}
