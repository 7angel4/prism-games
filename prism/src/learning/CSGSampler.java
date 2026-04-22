package learning;

import explicit.CSG;
import explicit.CSGSimple;
import explicit.Distribution;
import org.jfree.data.Value;
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


    protected void sampleTrajectory(
            CSGSimple<Double> trueGame,
            Strategy<Double> strategy,
            List<List<Long>> slotCounts,
            List<List<Map<Integer, Long>>> transitionCounts,
            int horizon,
            BitSet target
    ) throws PrismException {

        EpisodeTrace trace = new EpisodeTrace();

        int s = trueGame.getFirstInitialState();
        trace.setInitialState(s);

        int m;
        try {
            m = strategy.getInitialMemory(s);
        } catch (UnsupportedOperationException e) {
            m = -1;
        }

        int step = 0;
        Set<Integer> visited = new HashSet<>();

        while (step < horizon) {
            if (target != null && target.get(s)) {
                break;
            }

            if (isTerminal(trueGame, s, m, strategy)) {
                break; // loop detected
            }

            int c;
            try {
                c = strategy.getChoiceIndex(s, m);
            } catch (UnsupportedOperationException e) {
                throw new PrismException("Strategy does not support getChoiceIndex at state " + s);
            }

            if (c < 0) {
                break; // undefined action
            }

            int sp = sampleSuccessor(trueGame, s, c);

            trace.add(s, c, sp);
            updateCount(s, c, sp, slotCounts, transitionCounts);

            try {
                m = strategy.getUpdatedMemory(m, c, sp);
            } catch (UnsupportedOperationException e) {
                m = -1; // fallback for memoryless strategies
            }

            s = sp;
            step++;
        }
//        return trace;
    }

    private boolean isTerminal(CSGSimple<Double> model, int s, int m, Strategy<Double> strategy) {
        int c = strategy.getChoiceIndex(s, m);
        return isAbsorbing(model, s, c);
    }

    private boolean isAbsorbing(CSGSimple<Double> model, int s, int c) {
        Distribution<Double> choices = model.getChoice(s, c);
        return (choices.size() == 1 && choices.getSupport().contains(s));
    }

    protected void updateCount(int s, int c, int succ,
                               List<List<Long>> slotCounts,
                               List<List<Map<Integer, Long>>> transitionCounts) {
        long pairOrig = slotCounts.get(s).get(c);
        slotCounts.get(s).set(c, pairOrig + 1L);
        long tripleOrig = transitionCounts.get(s).get(c).getOrDefault(succ, 0L);
        transitionCounts.get(s).get(c).put(succ, tripleOrig + 1L);
    }
}
