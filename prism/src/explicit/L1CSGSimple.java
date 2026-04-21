package explicit;

import explicit.rewards.CSGRewards;
import explicit.rewards.MDPRewardsSimple;
import prism.Evaluator;
import prism.ModelType;
import prism.Pair;
import prism.PrismException;
import strat.MDStrategy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * Explicit-state L1 concurrent stochastic game.
 *
 * This is the CSG analogue of L1MDPSimple:
 * - the stored model is the nominal CSG;
 * - each choice carries an L1 radius;
 * - robust / optimistic distributions are generated on demand by shifting mass
 *   within the L1 ball.
 *
 * The existing CSGModelChecker can use this class as long as it can query
 * getDoubleTransitionsIterator(...) / getDoubleChoice(...).
 */
public class L1CSGSimple<Value> extends CSGSimple<Value> implements L1CSG<Value>
{
    public static final double INIT_RADIUS = 2.0;
    /** L1 radius per state/choice. */
    protected List<List<Double>> radii = new ArrayList<>();

    /** Cached extremal distributions returned by the last robustness query. */
    protected final Map<Integer, Map<Integer, Map<Integer, Double>>> chosenTransitions = new HashMap<>();

    private static final double EPS = 1e-15;

    private boolean minUncertainty = true;

    /**
     * Constructor: empty L1 CSG.
     */
    public L1CSGSimple()
    {
        super();
        createDefaultEvaluatorForCSG();
    }

    /**
     * Copy constructor that copies the CSG structure and metadata,
     * but does not copy the precise transition probabilities.
     * The copied choices keep the same supports and action profiles,
     * with nominal probabilities reset to a uniform distribution on
     * the original support.
     */
    public L1CSGSimple(CSGSimple<Value> template, List<List<Distribution<Value>>> trans)
    {
        super(template, trans);
        // Copy choice structure, but not exact probabilities
//        for (int s = 0; s < template.getNumStates(); s++) {
//            for (int c = 0; c < template.getNumChoices(s); c++) {
//                int[] profile = template.getIndexes(s, c);
//                profile = padJointActionProfile(profile, template.getNumPlayers());
//                // super should've used probMap, so the new probs are already set
//                Distribution<Value> distr = this.getChoice(s, c);
//                addActionLabelledChoice(s, distr, 0.0, profile);
//            }
//        }
        copyPlayerInfo(template);
        // Radii mirror the choice structure
        initialiseRadiiFrom(template);

        // add idle indexes
        int[] idles = template.getIdles();
        this.setIdles(Arrays.copyOf(idles, idles.length));

        this.chosenTransitions.clear();
    }


//    @Override
//    public void setCentre(int s, int i, Distribution<Value> distr) {
//        setTrans(s, i, distr);
//    }

    @Override
    public void setCentre(int s, int i, int succ, Value p) {
        getChoice(s, i).set(succ, p);
    }

    private void initialiseRadiiFrom(CSG<Value> other)
    {
        for (int s = 0; s < other.getNumStates(); s++) {
            int numChoices = other.getNumChoices(s);
            List<Double> row = new ArrayList<>(numChoices);
            for (int c = 0; c < numChoices; c++) {
                row.add(INIT_RADIUS);
            }
            radii.add(row);
        }
    }

    private static int[] padJointActionProfile(int[] profile, int numPlayers) {
        if (profile == null) {
            int[] out = new int[numPlayers];
            Arrays.fill(out, -1);
            return out;
        }
        if (profile.length == numPlayers) {
            return profile;
        }
        if (profile.length > numPlayers) {
            return Arrays.copyOf(profile, numPlayers);
        }

        int[] out = new int[numPlayers];
        Arrays.fill(out, -1);
        System.arraycopy(profile, 0, out, 0, profile.length);
        return out;
    }


    /**
     * Copy constructor with a state permutation.
     */
    public L1CSGSimple(L1CSGSimple<Value> other, int[] permut)
    {
        super(other, permut);
        this.radii = permuteRadii(other.radii, permut);
        this.chosenTransitions.clear();
    }

    @Override
    public CSG<?> getCSGModel()
    {
        return this;
    }

    /** Cache of deviation values: key = supports + quantised mixing, value = [devValP0, devValP1, ...] */
    private static final ConcurrentHashMap<String, double[]> devCache = new ConcurrentHashMap<>();

    private String makeDevKey(List<Map<BitSet, Double>> strat) {
        StringBuilder sb = new StringBuilder();
        for (Map<BitSet, Double> m : strat) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<BitSet, Double> e : m.entrySet()) {
                double q = Math.round(e.getValue() * 1000.0) / 1000.0;
                parts.add(Arrays.toString(e.getKey().toLongArray()) + "=" + q);
            }
            Collections.sort(parts);
            for (String p : parts) {
                sb.append(p).append(";");
            }
            sb.append("|");
        }
        return sb.toString();
    }

    /**
     * Extract the coalition-action BitSet from a joint action index array.
     * Players outside the coalition are ignored by the BitSet membership test.
     */
    private BitSet extractCoalitionActionIndexes(int[] jointIndexes, BitSet coalitionActions) {
        BitSet bs = new BitSet();
        for (int p = 0; p < jointIndexes.length; p++) {
            int idx = jointIndexes[p];
            if (idx < 0) {
                idx = getIdleForPlayer(p);
            }
            if (coalitionActions.get(idx)) {
                bs.set(idx);
            }
        }
        return bs;
    }

    @Override
    public boolean filterNEforRNE(double[][] eqVal,
                                  List<List<Map<BitSet, Double>>> strats,
                                  List<CSGRewards<Double>> csgRewards,
                                  BitSet[] coalitionIndexes,
                                  int s,
                                  boolean min,
                                  double[][] val) throws PrismException {
        if (strats == null) {
            return false;
        }

        AtomicBoolean anyNE = new AtomicBoolean(false);

        IntStream.range(0, eqVal.length).parallel().forEach(i -> {
            if (eqVal[i] != null && strats.get(i) != null) {
                boolean robust = isRobustNE(eqVal[i], strats.get(i), csgRewards, coalitionIndexes, s, min, val);
                if (!robust) {
                    eqVal[i] = null;
                    strats.set(i, null);
                } else {
                    anyNE.set(true);
                }
            }
        });

        return anyNE.get();
    }

    @Override
    public double[] findRNE(double[][] eqVal,
                            List<List<Map<BitSet, Double>>> strats,
                            List<CSGRewards<Double>> csgRewards,
                            BitSet[] coalitionIndexes,
                            int s,
                            boolean min,
                            double[][] val) throws PrismException
    {
        if (strats == null) {
            return null;
        }

        for (int i = 0; i < eqVal.length; i++) {
            if (eqVal[i] != null && strats.get(i) != null
                    && isRobustNE(eqVal[i], strats.get(i), csgRewards, coalitionIndexes, s, min, val)) {
                double[] equilibrium = new double[eqVal[i].length + 1];
                equilibrium[0] = 0.0;
                for (int p = 0; p < eqVal[i].length; p++) {
                    equilibrium[p + 1] = eqVal[i][p];
                    equilibrium[0] += eqVal[i][p];
                }
                return equilibrium;
            }
        }

        return null;
    }

    private boolean isRobustNE(double[] eqVal,
                               List<Map<BitSet, Double>> strat,
                               List<CSGRewards<Double>> csgRewards,
                               BitSet[] actionIndexes,
                               int s,
                               boolean min,
                               double[][] val)
    {
        if (strat == null) {
            return false;
        }

        int numPlayers = strat.size();
//        if (numPlayers != 2) {
//            throw new PrismException("Robust NE filtering currently expects 2 coalitions.");
//        }

        String key = makeDevKey(strat);
        double[] cached = devCache.get(key);

        AtomicBoolean ok = new AtomicBoolean(true);

        IntStream.range(0, numPlayers).parallel().forEach(p -> {
            if (!ok.get()) {
                return;
            }

            double devVal;
            if (cached != null && cached.length > p && !Double.isNaN(cached[p])) {
                devVal = cached[p];
            } else {
                CSGRewards<Double> rewards = (csgRewards == null) ? null : csgRewards.get(p);
                DevGainL1MDP devL1MDP = new DevGainL1MDP(this, p, strat, rewards, actionIndexes);
                devVal = devL1MDP.computeOptimisticValue(min, val[p])[s];
            }

            if (devVal > eqVal[p] + EPS) {
                ok.set(false);
            }
        });

        return ok.get();
    }

    /**
     * Deviation game for L1 uncertainty.
     * This is the L1 analogue of the IMDP-based deviation model used in ICSGSimple.
     */
    private final class DevGainL1MDP extends L1MDPSimple<Double> {
        private final MDPRewardsSimple<Double> rewards;
        private final Map<BitSet, Double> agentStrat;
        private final Map<BitSet, Double> otherStrat;
        private final BitSet agentActions;
        private final BitSet otherActions;
        private final List<BitSet> agentIndexes;
        private boolean buildFull = true;

        private final ConcurrentMap<Pair<Set<BitSet>, Map<BitSet, Double>>, double[]> optimisticValCache = new ConcurrentHashMap<>();

        private class ChoiceResult {
            final int a;
            final double expRewDev;
            final double radius;
            final Distribution<Double> distr;

            ChoiceResult(int a, double expRewDev, double radius, Distribution<Double> distr) {
                this.a = a;
                this.expRewDev = expRewDev;
                this.radius = radius;
                this.distr = distr;
            }
        }

        DevGainL1MDP(L1CSGSimple<Value> csg,
                     int agent,
                     List<Map<BitSet, Double>> strat,
                     CSGRewards<Double> csgRewards,
                     BitSet[] actionIndexes)
        {
            super(csg.getNumStates());

            this.rewards = (csgRewards == null) ? null : new MDPRewardsSimple<>(csg.getNumStates());
            this.agentStrat = strat.get(agent);
            this.otherStrat = strat.get(1 - agent);
            this.agentActions = actionIndexes[agent];
            this.otherActions = actionIndexes[1 - agent];
            this.agentIndexes = new ArrayList<>(agentStrat.keySet());

            Pair<Set<BitSet>, Map<BitSet, Double>> cacheKey = new Pair<>(new HashSet<>(agentIndexes), otherStrat);
            if (optimisticValCache.containsKey(cacheKey)) {
                buildFull = false;
                return;
            }

            for (int s = 0; s < csg.getNumStates(); s++) {
                final int state = s;

                List<ChoiceResult> results = IntStream.range(0, agentIndexes.size()).parallel()
                        .mapToObj(a -> {
                            double expRewDev = 0.0;
                            double radius = 0.0;
                            Distribution<Double> nominal = new Distribution<>(Evaluator.forDouble());

                            for (int choiceIdx = 0; choiceIdx < csg.getNumChoices(state); choiceIdx++) {
                                int[] jointIndexes = csg.getIndexes(state, choiceIdx);

                                BitSet agentAct = csg.extractCoalitionActionIndexes(jointIndexes, agentActions);
                                BitSet otherAct = csg.extractCoalitionActionIndexes(jointIndexes, otherActions);

                                if (!agentAct.equals(agentIndexes.get(a))) {
                                    continue;
                                }

                                double probOther = otherStrat.getOrDefault(otherAct, 0.0);
                                if (probOther <= 0.0) {
                                    continue;
                                }

                                if (csgRewards != null) {
                                    expRewDev += probOther * csgRewards.getTransitionReward(state, choiceIdx);
                                }

                                Distribution<Value> distr = getNominalChoice(state, choiceIdx);
                                for (Map.Entry<Integer, Value> e : distr) {
                                    nominal.add(e.getKey(), probOther * ((double) e.getValue()));
                                }

                                // Safe aggregation of L1 radii under mixing
                                radius += probOther * csg.getRadius(state, choiceIdx);
                            }

                            return new ChoiceResult(a, expRewDev, radius, nominal);
                        })
                        .toList();

                for (ChoiceResult r : results) {
                    addActionLabelledChoice(state, r.distr, r.radius, agentIndexes.get(r.a));
                    if (csgRewards != null) {
                        rewards.setTransitionReward(state, getNumChoices(state) - 1, r.expRewDev);
                    }
                }
            }

            optimisticValCache.put(cacheKey, new double[csg.getNumStates()]);
        }

        double[] computeOptimisticValue(boolean min, double[] val) {
            if (!buildFull) {
                // Cache hit: if you want to make this fully exact, store the value vector
                // in the cache after the first solve and return it here.
                double[] cached = optimisticValCache.values().iterator().next();
                if (cached != null && cached.length == getNumStates()) {
                    return cached;
                }
            }

            MinMax minMax = min ? MinMax.min().setMinUnc(true)
                    : MinMax.max().setMinUnc(false);

            double[] result = new double[getNumStates()];
            if (this.rewards == null) {
                ((L1MDP<Double>) this).mvMultUnc(val, minMax, result, null, false, null);
            } else {
                ((L1MDP<Double>) this).mvMultRewUnc(val, this.rewards, minMax, result, null, false, null);
            }

            return result;
        }
    }

    /**
     * Default evaluator for double-based CSGs.
     */
    @SuppressWarnings("unchecked")
    private void createDefaultEvaluatorForCSG()
    {
        Evaluator<Value> eval = (Evaluator<Value>) Evaluator.forDouble();
        super.setEvaluator(eval);
    }

    @Override
    public void setEvaluator(Evaluator<Value> evaluator)
    {
        super.setEvaluator(evaluator);
    }

    private static List<List<Double>> permuteRadii(List<List<Double>> oldRadii, int[] permut)
    {
        List<List<Double>> newRadii = new ArrayList<>(oldRadii.size());
        for (int i = 0; i < oldRadii.size(); i++) {
            newRadii.add(new ArrayList<>());
        }

        for (int oldS = 0; oldS < permut.length; oldS++) {
            int newS = permut[oldS];
            List<Double> src = (oldS < oldRadii.size()) ? oldRadii.get(oldS) : new ArrayList<>();
            newRadii.set(newS, new ArrayList<>(src));
        }

        return newRadii;
    }

    private void appendRadius(int s, double radius)
    {
        radii.get(s).add(radius);
    }

    @Override
    public void setRadius(int s, int c, double r) {
        List<Double> rs = radii.get(s);
        while (rs.size() <= c) {
            rs.add(INIT_RADIUS);
        }
        rs.set(c, r);
    }

    @Override
    public double getRadius(int s, int a)
    {
        if (s < 0 || s >= radii.size()) {
            return 0.0;
        }
        List<Double> rs = radii.get(s);
        if (a < 0 || a >= rs.size()) {
            return 0.0;
        }
        return rs.get(a);
    }

    @Override
    public void clearState(int s)
    {
        super.clearState(s);
        radii.get(s).clear();
        chosenTransitions.remove(s);
    }

    @Override
    public int addState()
    {
        int i = super.addState();
        radii.add(new ArrayList<>());
        return i;
    }

    @Override
    public void addStates(int numToAdd)
    {
        super.addStates(numToAdd);
        for (int i = 0; i < numToAdd; i++) {
            radii.add(new ArrayList<>());
        }
    }

    /**
     * Add a labelled joint-action choice together with its L1 radius.
     */
    public int addActionLabelledChoice(int s, Distribution<Value> distr, double radius, Object action)
    {
        int i = super.addActionLabelledChoice(s, distr, action);
        if (i != -1) {
            if (radii.size() <= s) radii.add(new ArrayList<>());
            radii.get(s).add(radius);
        }
        return i;
    }

    /**
     * Add a labelled indexed choice together with its L1 radius.
     */
    public int addActionLabelledChoice(int s, Distribution<Value> distr, double radius, int[] indexes)
    {
        int i = super.addActionLabelledChoice(s, distr, indexes);
        if (i != -1) {
            if (radii.size() <= s) radii.add(new ArrayList<>());
            radii.get(s).add(radius);
        }
        return i;
    }

    /**
     * Backwards-compatible overloads: if radius is omitted, default to 0.
     * This keeps the internal radii list aligned with the choice list.
     */
    @Override
    public int addActionLabelledChoice(int s, Distribution<Value> distr, Object action) {
        return this.addActionLabelledChoice(s,  distr, INIT_RADIUS, action);
    }

    @Override
    public int addActionLabelledChoice(int s, Distribution<Value> distr, int[] indexes) {
        return this.addActionLabelledChoice(s,  distr, INIT_RADIUS, indexes);
    }

    @Override
    public ModelType getModelType() {
        return ModelType.L1CSG;
    }

    /**
     * Convert an arbitrary Value distribution to a Double distribution.
     */
    private Distribution<Double> toDoubleDistribution(Distribution<Value> distr)
    {
        Distribution<Double> out = new Distribution<>(Evaluator.forDouble());
        for (Map.Entry<Integer, Value> e : distr) {
            out.add(e.getKey(), ((Number) e.getValue()).doubleValue());
        }
        return out;
    }

    /**
     * Build the extremal distribution under the L1 ball around the nominal distribution.
     * If val == null, returns the nominal distribution (this is enough for graph-only precomputation).
     */
    private Distribution<Double> buildExtremeDistribution(Distribution<Value> distr, double radius, double[] val, MinMax minMax)
    {
        if (distr == null || distr.isEmpty()) {
            return new Distribution<>(Evaluator.forDouble());
        }

        if (val == null) {
            return toDoubleDistribution(distr);
        }

        int n = distr.size();
        if (n == 1) {
            return toDoubleDistribution(distr);
        }

        int[] stateIndex = new int[n];
        double[] p = new double[n];
        double[] values = new double[n];

        int t = 0;
        for (Map.Entry<Integer, Value> entry : distr) {
            stateIndex[t] = entry.getKey();
            p[t] = ((Number) entry.getValue()).doubleValue();
            values[t] = val[stateIndex[t]];
            t++;
        }

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }

        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));

        double budget = Math.max(0.0, radius) / 2.0;
        if (budget <= EPS) {
            return toDoubleDistribution(distr);
        }

        boolean minimizeUncertainty = minMax.isMinUnc();

        if (minimizeUncertainty) {
            // Move mass from high-value states to low-value states.
            int low = 0;
            int high = n - 1;

            while (budget > EPS && low < high) {
                while (low < high && p[order[low]] >= 1.0 - EPS) low++;
                while (low < high && p[order[high]] <= EPS) high--;
                if (low >= high) {
                    break;
                }

                int from = order[high];
                int to = order[low];
                double delta = Math.min(budget, Math.min(p[from], 1.0 - p[to]));
                if (delta <= EPS) {
                    if (p[from] <= EPS) high--;
                    if (p[to] >= 1.0 - EPS) low++;
                    continue;
                }

                p[from] -= delta;
                p[to] += delta;
                budget -= delta;

                if (p[from] <= EPS) low++;
                if (p[to] >= 1.0 - EPS) high--;
            }
        } else {
            // Move mass from low-value states to high-value states.
            int low = 0;
            int high = n - 1;

            while (budget > EPS && low < high) {
                while (low < high && p[order[low]] <= EPS) low++;
                while (low < high && p[order[high]] >= 1.0 - EPS) high--;
                if (low >= high) {
                    break;
                }

                int from = order[low];
                int to = order[high];
                double delta = Math.min(budget, Math.min(p[from], 1.0 - p[to]));
                if (delta <= EPS) {
                    if (p[from] <= EPS) low++;
                    if (p[to] >= 1.0 - EPS) high--;
                    continue;
                }

                p[from] -= delta;
                p[to] += delta;
                budget -= delta;

                if (p[from] <= EPS) low++;
                if (p[to] >= 1.0 - EPS) high--;
            }
        }

        Distribution<Double> out = new Distribution<>(Evaluator.forDouble());
        for (int i = 0; i < n; i++) {
            if (p[i] > EPS) {
                out.add(stateIndex[i], p[i]);
            }
        }
        return out;
    }

    /**
     * Matrix-vector backup for a single choice.
     * For L1 CSG, this is the robust/optimistic expectation under the L1 ball.
     */
    public double mvMultUncSingle(int s, int k, double[] vect, MinMax minMax)
    {
        Distribution<?> distr = getChoice(s, k);
        double radius = getRadius(s, k);
        Distribution<Double> extreme = buildExtremeDistribution((Distribution<Value>) distr, radius, vect, minMax);

        double res = 0.0;
        for (Map.Entry<Integer, Double> e : extreme) {
            res += e.getValue() * vect[e.getKey()];
        }
        return res;
    }


    public void setMinUncertainty(boolean minUncertainty) {
        this.minUncertainty = minUncertainty;
    }

    /**
     * CSGModelChecker asks for a double-valued transition iterator.
     * We return the extremal L1 distribution here.
     */
    @Override
    public Iterator<Map.Entry<Integer, Double>> getDoubleTransitionsIterator(int s, int i, double val[])
    {
        Distribution<Value> distr = getNominalChoice(s, i);
        double radius = getRadius(s, i);

        MinMax minMax = MinMax.minMin(false, true).setMinUnc(minUncertainty);
        Distribution<Double> extreme = buildExtremeDistribution(distr, radius, val, minMax);

        chosenTransitions.computeIfAbsent(s, ss -> new HashMap<>())
                .put(i, new HashMap<>());

        Map<Integer, Double> cache = chosenTransitions.get(s).get(i);
        for (Map.Entry<Integer, Double> e : extreme) {
            cache.put(e.getKey(), e.getValue());
        }

        return extreme.iterator();
    }

    @Override
    public void forEachDoubleTransition(int s, int i, double[] val, MDP.TransitionConsumer<Double> c) {
        // Both CSG and UCSG provide the same default; pick one explicitly.
        L1CSG.super.forEachDoubleTransition(s, i, val, c);
    }

    @Override
    public Distribution<Double> getDoubleChoice(int s, int i, double val[])
    {
        return Distribution.ofDouble(this.getDoubleTransitionsIterator(s, i, val));
    }

    @Override
    public Iterator<Map.Entry<Integer, Double>> getChosenTransitionsIterator(int s, int i)
    {
        Map<Integer, Map<Integer, Double>> byState = chosenTransitions.get(s);
        if (byState == null) {
            return Collections.<Integer, Double>emptyMap().entrySet().iterator();
        }
        Map<Integer, Double> byChoice = byState.get(i);
        if (byChoice == null) {
            return Collections.<Integer, Double>emptyMap().entrySet().iterator();
        }
        return byChoice.entrySet().iterator();
    }

    @Override
    public Model<Value> constructInducedModel(MDStrategy<Value> strat)
    {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * L1 balls do not rely on explicit lower bounds being positive.
     */
    public void checkLowerBoundsArePositive() throws PrismException
    {
        // no-op
    }

    @Override
    public void exportToPrismLanguage(String filename, int precision) throws PrismException
    {
        super.exportToPrismLanguage(filename, precision);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof L1CSGSimple<?> other)) {
            return false;
        }
        return super.equals(o) && this.radii.equals(other.radii);
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        s.append("[ ");

        for (int i = 0; i < numStates; i++) {
            if (i > 0) {
                s.append(", ");
            }
            s.append(i).append(": ");
            s.append("[");
            int n = getNumChoices(i);
            for (int j = 0; j < n; j++) {
                if (j > 0) {
                    s.append(",");
                }
                Object o = getAction(i, j);
                if (o != null) {
                    s.append(o).append(":");
                }
                s.append(trans.get(i).get(j));
                s.append(", Radius: ");
                s.append(getRadius(i, j));
            }
            s.append("]");
        }

        s.append(" ]\n");
        return s.toString();
    }
}