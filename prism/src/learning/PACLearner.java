package learning;

import explicit.*;
import explicit.rewards.CSGRewards;
import explicit.rewards.MDPRewardsSimple;
import learning.Simulation.TransitionTriple;
import parser.ast.Coalition;
import parser.ast.ExpressionTemporal;
import prism.*;
import strat.CSGStrategy;
import strat.Strategy;

import java.util.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class PACLearner {

    public enum ObjectiveKind {
        PROB_REACH,
        REW_REACH,
        PROB_REACH_BOUNDED,
        REW_BOUNDED,
        MIXED,
        ZERO_SUM_PROB_REACH,
        ZERO_SUM_REW_REACH
    }

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
                         final CSGStrategy<Double> robustStrategy) {
            this.certificateNoExactNE = certificateNoExactNE;
            this.terminatedByAllKnown = terminatedByAllKnown;
            this.episodes = episodes;
            this.robustValue = robustValue;
            this.deltaT = deltaT;
            this.robustStrategy = robustStrategy;
        }
    }

    // TODO: Replace with actual trajectory type
    private static final class EpisodeTrace {
        final List<TransitionTriple> steps = new ArrayList<>();
        int initialState = -1;

        void add(int s, String a, int sp) {
            steps.add(new TransitionTriple(s, a, sp));
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
    }

    private Prism prism;
    private Experiment experiment;

    private final List<List<Long>> slotCounts = new ArrayList<>();
    private final List<List<Map<Integer, Long>>> transitionCounts = new ArrayList<>();
    private final List<List<Boolean>> known = new ArrayList<>();
    private L1CSGSimple<Double> empiricalGame;
    private L1MDPSimple<Double> explorationRMDP;
    private long nMin;
    private double maxRadius = 0.0;
    private MDPRewardsSimple<Double> explorationRewards;
    private BitSet explorationTarget;
    private CSGSampler sampler;

    public PACLearner(Prism prism, Experiment ex, int seed) {
        this.prism = prism;
        this.experiment = ex;
        this.sampler = new CSGSampler(seed);
    }

    private void checkValidInput(CSGSimple<Double> trueGame, List<Coalition> coalitions, BitSet[] targets,
                                 double epsilon, double delta, int horizon) throws PrismException {
        if (trueGame == null) {
            throw new PrismException("trueGame is null");
        }
        if (coalitions == null || coalitions.isEmpty()) {
            throw new PrismException("coalitions must be provided");
        }
        if (targets == null || targets.length == 0) {
            throw new PrismException("targets must be provided");
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

    public PacResult runPacLoop(
            CSGSimple<Double> trueGame,
            ObjectiveKind objectiveKind,
            List<Coalition> coalitions,
            List<ExpressionTemporal> exprs,
            List<CSGRewards<Double>> rewards,
            BitSet[] targets,
            BitSet[] remain,
            int[] bounds, int eqType, int crit, boolean min,
            double eps, double delta, double rMax, int horizon
    ) throws PrismException {

        checkValidInput(trueGame, coalitions, targets, eps, delta, horizon);
        final double deltaContain = delta / 2.0;
        int episode = 0;
        computeNmin(trueGame.getNumStates(), trueGame.getActions().size(), deltaContain, rMax, horizon, eps);
        initialiseRun(trueGame); // empiricalGame constructed
        // `remain` should be the entire state space
//        if ((objectiveKind == ObjectiveKind.PROB_REACH || objectiveKind == ObjectiveKind.ZERO_SUM_PROB_REACH)
//                && remain == null) {
//            remain = defaultRemainSets(trueGame, targets);
//        }

        while (true) {
            episode++;
            updatedL1Transitions(deltaContain);
            double deltaT = computeDeltaT(rMax, horizon);
            SolveOutcome robustSol = robustSolveL1CSG(objectiveKind, coalitions, exprs, rewards, targets,
                    remain, bounds, eqType, crit, min);
            boolean allKnown = allSlotsKnown();
            if (!robustSol.found && deltaT <= eps / STOP_THRESH_FACTOR) {
                return new PacResult(true, false, episode, robustSol.value, deltaT, robustSol.strategy);
            }
            if (robustSol.found && (deltaT <= eps / STOP_THRESH_FACTOR || allKnown)) {
                return new PacResult(false, allKnown, episode, robustSol.value, deltaT, robustSol.strategy);
            }

            explorationRMDP = new L1MDPSimple(empiricalGame);
            updateExplorationRewards();
            Strategy<Double> exploreStrat = solveExplorationRMDP(horizon, objectiveKind);

            sampler.sampleTrajectory(trueGame, exploreStrat, slotCounts, transitionCounts,
                    horizon, explorationTarget, true);
            updateKnown();
        }
    }

    private double lambertW(double x) {
        return Math.log(x) - Math.log(Math.log(x));
    }

    private void computeNmin(int numStates, int numChoices, double deltaContain, double rMax, int horizon, double eps) {
        double c = -16 * rMax * rMax * Math.pow(horizon, 4.0) / (eps * eps);
        double x = Math.sqrt(deltaContain / ((Math.pow(2, numStates) - 2.0) * numStates * numChoices)) / c;
        double n = c * lambertW(x);
        nMin = (Double.isNaN(n) || Double.isInfinite(n) || n < 1.0) ? 1L : Math.round(n);
    }
    private void initialiseRun(CSGSimple<Double> template) {
        slotCounts.clear();
        transitionCounts.clear();
        known.clear();

        int numStates = template.getNumStates();
        List<List<Distribution<Double>>> trans = new ArrayList<>();

        for (int s = 0; s < numStates; s++) {
            int numChoices = template.getNumChoices(s);

            known.add(new ArrayList<>(numChoices));
            trans.add(new ArrayList<>(numChoices));
            slotCounts.add(new ArrayList<>(numChoices));
            transitionCounts.add(new ArrayList<>(numChoices));

            for (int c = 0; c < numChoices; c++) {
                known.get(s).add(false);
                slotCounts.get(s).add(0L);

                List<Integer> succs = new ArrayList<>();
                Iterator<Integer> it = template.getSuccessorsIterator(s, c);
                while (it.hasNext()) {
                    int succ = it.next();
                    succs.add(succ);
                }

                Map<Integer, Long> succCounts = new HashMap<>();
                for (int succ : succs) {
                    succCounts.put(succ, 0L);
                }
                transitionCounts.get(s).add(succCounts);

                trans.get(s).add(getUniformDistr(succs));
            }
        }

        empiricalGame = new L1CSGSimple<>(template, trans);
        explorationRMDP = new L1MDPSimple<>(empiricalGame);
        explorationRewards = new MDPRewardsSimple<>(explorationRMDP.getNumStates());
        explorationTarget = new BitSet(empiricalGame.getNumStates());
    }

    private Distribution<Double> getUniformDistr(List<Integer> successors) {
        Distribution<Double> uniform = new Distribution<>(Evaluator.forDouble());
        if (successors.isEmpty()) {
            return uniform;
        }

        double p = 1.0 / successors.size();
        for (int succ : successors) {
            uniform.add(succ, p);
        }
        return uniform;
    }

    private void updatedL1Transitions(double deltaContain) {
        int numStates = empiricalGame.getNumStates();
        for (int s=0; s < numStates; s++) {
            for (int c=0; c < empiricalGame.getNumChoices(s); c++) {
                long saCount = slotCounts.get(s).get(c);
                if (saCount == 0L)  // keep radius 1 around uniform distr
                    continue;
                double deltaSlot = deltaContain / (empiricalGame.getNumChoices() * saCount * (saCount+1));
                double radius = weissmanRadius(saCount, deltaSlot);
                if (radius > maxRadius) maxRadius = radius;
                empiricalGame.setRadius(s, c, radius);
                int sFinal = s, cFinal = c;
                empiricalGame.getSuccessorsIterator(s, c).forEachRemaining(succ -> {
                    double pHat = transitionCounts.get(sFinal).get(cFinal).getOrDefault(succ, 0L) / saCount;
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
//                explorationTarget.set(s);
            }
        }
    }


    private SolveOutcome robustSolveL1CSG(
            ObjectiveKind objectiveKind,
            List<Coalition> coalitions,
            List<ExpressionTemporal> exprs,
            List<CSGRewards<Double>> rewards,
            BitSet[] targets,
            BitSet[] remain,
            int[] bounds,
            int eqType,
            int crit,
            boolean min
    ) throws PrismException {

        UCSGModelChecker mc = new UCSGModelChecker(this.prism);
        mc.setGenStrat(true);
        mc.setPrecomp(true);
        mc.setTermCritParam(1e-4);

        ModelCheckerResult res;
        try {
            switch (objectiveKind) {
                case PROB_REACH:
                    res = mc.computeProbReachEquilibria(empiricalGame, coalitions, targets, remain, eqType, crit, min);
                    break;
                case REW_REACH:
                    res = mc.computeRewReachEquilibria(empiricalGame, coalitions, rewards, targets, eqType, crit, min);
                    break;
                case PROB_REACH_BOUNDED:
                    res = mc.computeProbBoundedEquilibria(empiricalGame, coalitions, exprs, targets, remain, bounds, eqType, crit, min);
                    break;
                case REW_BOUNDED:
                    res = mc.computeRewBoundedEquilibria(empiricalGame, coalitions, rewards, null, bounds, eqType, crit, min);
                    break;
                default:
                    throw new PrismException("Unsupported objective kind: " + objectiveKind);
            }
        } catch (PrismException e) {
            return new SolveOutcome(false, null, Double.NaN);
        }

        if (res == null || res.soln == null) {
            return new SolveOutcome(false, null, Double.NaN);
        }

        double value = res.soln[empiricalGame.getFirstInitialState()];
        CSGStrategy<Double> strategy = (res.strat == null) ? null : (CSGStrategy<Double>) res.strat;
        return new SolveOutcome(strategy != null, strategy, value);
    }

    private Strategy<Double> solveExplorationRMDP(int horizon, ObjectiveKind objectiveType) throws PrismException {
        UMDPModelChecker mc = new UMDPModelChecker(this.prism);
        mc.setGenStrat(true);
        mc.setPrecomp(true);
//        mc.setErrorOnNonConverge(experiment.errorOnNonConvergence);

        ModelCheckerResult res;
        if (objectiveType != ObjectiveKind.PROB_REACH_BOUNDED && objectiveType != ObjectiveKind.REW_BOUNDED) {
            res = mc.computeReachRewards(explorationRMDP, explorationRewards, explorationTarget, MinMax.max().setMinUnc(true));
        } else {
            res = mc.computeCumulativeRewards(explorationRMDP, explorationRewards, horizon, MinMax.max().setMinUnc(true));
        }

        if (res == null || res.strat == null) {
            throw new PrismException("Exploration solver did not return a strategy.");
        }

        return (Strategy<Double>) res.strat;
    }

    private void updateCount(int s, int i) {
        long n = slotCounts.get(s).get(i);
        slotCounts.get(s).set(i, n+1);
    }

    private void updateKnown() {
        for (int s=0; s < empiricalGame.getNumStates(); s++) {
            for (int c=0; c < empiricalGame.getNumChoices(); c++) {
                known.get(s).set(c, slotCounts.get(s).get(c) >= nMin);
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

        PACLearner learner = new PACLearner(prism, ex, 41);

        Experiment.PacRunSpec spec = ex.buildPacRunSpec(prism);

        PACLearner.PacResult res = learner.runPacLoop(
                spec.trueGame,
                spec.objectiveKind,
                spec.coalitions,
                spec.exprs,
                spec.rewards,
                spec.targets,
                spec.remain,
                spec.bounds,
                spec.eqType,
                spec.crit,
                spec.min,
                spec.eps,
                spec.delta,
                spec.rMax,
                spec.horizon
        );

        System.out.println("certificateNoExactNE=" + res.certificateNoExactNE);
        System.out.println("terminatedByAllKnown=" + res.terminatedByAllKnown);
        System.out.println("episodes=" + res.episodes);
        System.out.println("robustValue=" + res.robustValue);
        System.out.println("deltaT=" + res.deltaT);
        System.out.println("robustStrategy=" + (res.robustStrategy != null));
    }
}