package learning;

import explicit.CSG;
import explicit.CSGSimple;
import explicit.Distribution;
import prism.PrismException;
import simulator.RandomNumberGenerator;
import strat.CSGStrategy;
import strat.Strategy;

import java.util.*;

public class CSGSampler {
    protected static final class EpisodeTrace {

        protected static final class Step {
            final int s;
            final int c;     // choice index
            final int sp;

            Step(int s, int c, int sp) {
                this.s = s;
                this.c = c;
                this.sp = sp;
            }
        }

        final List<Step> steps = new ArrayList<>();
        int initialState = -1;

        void setInitialState(int s0) {
            this.initialState = s0;
        }

        void add(int s, int c, int sp) {
            steps.add(new Step(s, c, sp));
        }

        int length() {
            return steps.size();
        }
    }

    private RandomNumberGenerator rng;
    public static final int NUM_COALITIONS = 2;

    public CSGSampler() {
        // default constructor
        this.rng = new RandomNumberGenerator();
    }

    public CSGSampler(int seed) {
        this.rng = new RandomNumberGenerator(seed);
    }

    private int sampleSuccessor(CSGSimple<Double> model, int s, int choiceIdx) {
        double r = rng.randomUnifDouble();
        double cum = 0.0;
        int lastSucc = -1;

        Iterator<Map.Entry<Integer, Double>> it = model.getChosenTransitionsIterator(s, choiceIdx);
        while (it.hasNext()) {
            Map.Entry<Integer, Double> e = it.next();
            lastSucc = e.getKey();
            cum += e.getValue();
            if (r <= cum) {
                return e.getKey();
            }
        }
        if (lastSucc >= 0) {
            return lastSucc;
        }
        throw new IllegalStateException("Empty transition distribution at state " + s + ", choice " + choiceIdx);
    }

    // match a sampled joint action back to a choice idnex
    private int findChoiceIndex(CSGSimple<Double> model, int s, BitSet jointAct) {
        for (int c = 0; c < model.getNumChoices(s); c++) {
            int[] idxs = model.getIndexes(s, c);
            BitSet candidate = new BitSet();
            for (int p = 0; p < idxs.length; p++) {
                int a = idxs[p];
                if (a > 0) candidate.set(a);
                else candidate.set(model.getIdleForPlayer(p));
            }
            if (candidate.equals(jointAct)) {
                return c;
            }
        }
        return -1;
    }


    protected EpisodeTrace sampleTrajectory(
            CSGSimple<Double> trueGame,
            Strategy<Double> strategy,
            List<List<Long>> slotCounts,
            List<List<Map<Integer, Long>>> transitionCounts,
            int horizon,
            BitSet target,
            boolean stopOnTarget
    ) throws PrismException {

        EpisodeTrace trace = new EpisodeTrace();

        int s = trueGame.getFirstInitialState();
        trace.setInitialState(s);

        int step = 0;

        while (step < horizon) {
            if (stopOnTarget && target != null && target.get(s)) {
                break;
            }

            int c = sampleChoiceIndex(strategy, s);
            if (c < 0) {
                break;
            }

            int sp = sampleSuccessor(trueGame, s, c);

            trace.add(s, c, sp);
            updateCount(s, c, sp, slotCounts, transitionCounts);

            s = sp;
            step++;
        }

        return trace;
    }

    private int sampleChoiceIndex(Strategy<Double> strategy, int s) throws PrismException {
        Object act = strategy.getChoiceAction(s, -1);

        if (act == Strategy.UNDEFINED) {
            return -1;
        }

        // deterministic case: act is Integer index
        if (act instanceof Integer) {
            return (Integer) act;
        }

        // randomized case: distribution over indices
        // PRISM typically uses DistributionOver<Object>
        if (act instanceof Distribution<?>) {
            Distribution<?> dist = (Distribution<?>) act;

            double r = rng.randomUnifDouble();
            double cum = 0.0;
            int last = -1;

            for (Object key : dist.getSupport()) {
                int idx = (Integer) key;
                double p = (Double) dist.get(idx);
                cum += p;
                last = idx;
                if (r <= cum) {
                    return idx;
                }
            }
            return last;
        }

        throw new PrismException("Unsupported strategy action type: " + act.getClass());
    }


    protected void updateCount(int s, int c, int succ,
                             List<List<Long>> slotCounts,
                             List<List<Map<Integer, Long>>> transitionCounts) {
        long pairOrig = slotCounts.get(s).size() > c ? slotCounts.get(s).get(c) : 0L;
        slotCounts.get(s).set(c, pairOrig + 1L);
        long tripleOrig = transitionCounts.get(s).get(c).getOrDefault(succ, 0L);
        transitionCounts.get(s).get(c).putIfAbsent(succ, tripleOrig + 1L);
    }

    private BitSet sampleFromDistribution(Map<BitSet, Double> dist) throws PrismException {
        if (dist == null || dist.isEmpty()) {
            return null;
        }

        double total = 0.0;
        for (double p : dist.values()) {
            if (p > 0.0) total += p;
        }
        if (total <= 0.0) {
            return null;
        }

        double r = rng.randomUnifDouble() * total;
        double cum = 0.0;
        BitSet last = null;

        for (Map.Entry<BitSet, Double> e : dist.entrySet()) {
            double p = e.getValue();
            if (p <= 0.0) continue;
            cum += p;
            last = e.getKey();
            if (r <= cum) {
                return (BitSet) e.getKey().clone();
            }
        }

        return last == null ? null : (BitSet) last.clone();
    }
}
