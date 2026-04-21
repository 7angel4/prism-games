package explicit;

import prism.Evaluator;
import prism.PrismException;
import strat.MDStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class L1MDPSimple<Value> extends ModelExplicit<Value> implements NondetModelSimple<Value>, L1MDP<Value> {

    // Center MDP - empirical distribution
    protected MDPSimple<Value> mdp;
    protected List<List<Double>> radii = new ArrayList<>();

    public L1MDPSimple() {
        mdp = new MDPSimple<>();
        createDefaultEvaluatorForMDP();
        initialise(0);
    }

    public L1MDPSimple(int numStates) {
        mdp = new MDPSimple<>(numStates);
        createDefaultEvaluatorForMDP();
        initialise(numStates);
    }

    public L1MDPSimple(L1MDPSimple<Value> other, int[] permut) {
        mdp = new MDPSimple<>(other.mdp, permut);
        initialise(other.numStates);
        super.copyFrom(other, permut);
        this.radii = permuteRadii(other.radii, permut);
        this.setEvaluator(other.getEvaluator());
        this.setStatesList(null);
        this.setConstantValues(other.getConstantValues());
        this.setVarList(other.getVarList());
    }

    public L1MDPSimple(L1CSGSimple<Value> other) {
        mdp = new MDPSimple<>(other);
        radii.clear();
        for (int s = 0; s < getNumStates(); s++) {
            radii.add(new ArrayList<>());
            for (int c = 0; c < getNumChoices(s); s++) {
                radii.get(s).add(((L1CSG<Value>) other).getRadius(s, c));
            }
        }
    }

    private static List<List<Double>> permuteRadii(List<List<Double>> oldRadii, int[] permut) {
        List<List<Double>> newRadii = new ArrayList<>(oldRadii.size());
        for (int i = 0; i < oldRadii.size(); i++) {
            newRadii.add(new ArrayList<>());
        }
        for (int oldS = 0; oldS < permut.length; oldS++) {
            int newS = permut[oldS];
            newRadii.set(newS, new ArrayList<>(oldRadii.get(oldS)));
        }
        return newRadii;
    }

    /**
     * Add a default evaluator to the MDP wrapper and the backing MDP.
     */
    @SuppressWarnings("unchecked")
    private void createDefaultEvaluatorForMDP() {
        Evaluator<Value> eval = (Evaluator<Value>) Evaluator.forDouble();
        super.setEvaluator(eval);
        mdp.setEvaluator(eval);
    }

    @Override
    public void setEvaluator(Evaluator<Value> evaluator) {
        super.setEvaluator(evaluator);
        this.mdp.setEvaluator(evaluator);
    }

    public void addActionLabelledChoice(int s, Distribution<Value> distr, double radius, Object action) {
        int success = mdp.addActionLabelledChoice(s, distr, action);
        if (success != -1) {
            while (radii.size() <= s) {
                radii.add(new ArrayList<>());
            }
            radii.get(s).add(radius);
        }
    }

    @Override
    public void buildFromPrismExplicit(String filename) throws PrismException {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void setRadius(int s, int a, double r) {
        radii.get(s).set(a, r);
    }

    @Override
    public void clearState(int s) {
        mdp.clearState(s);
    }

    @Override
    public int addState() {
        addStates(1);
        return numStates - 1;
    }

    @Override
    public void addStates(int numToAdd) {
        mdp.addStates(numToAdd);
        numStates += numToAdd;
        for (int i = 0; i < numToAdd; i++) {
            radii.add(new ArrayList<>());
        }
    }

    @Override
    public void findDeadlocks(boolean fix) throws PrismException {
        mdp.findDeadlocks(fix);
    }

    @Override
    public void checkForDeadlocks(BitSet except) throws PrismException {
        mdp.checkForDeadlocks(except);
    }

    @Override
    public double mvMultUncSingle(int s, int k, double[] vect, MinMax minMax) {
        Distribution<Value> distr = mdp.trans.get(s).get(k);
        double radius = radii.get(s).get(k);
        return L1SupportFunction.solve(distr, radius, vect, minMax);
    }

    @Override
    public int getNumChoices(int s) {
        return mdp.getNumChoices(s);
    }

    @Override
    public Object getAction(int s, int i) {
        return mdp.getAction(s, i);
    }

    @Override
    public int getNumTransitions(int s, int i) {
        return mdp.getNumTransitions(s, i);
    }

    @Override
    public SuccessorsIterator getSuccessors(int s, int i) {
        return mdp.getSuccessors(s, i);
    }

    @Override
    public Model<Value> constructInducedModel(MDStrategy<Value> strat) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void checkLowerBoundsArePositive() throws PrismException {
        // not needed for L1 robust backup
    }

    public void doSomething() {
    }

    @Override
    public String toString() {
        String s = "[ ";
        for (int i = 0; i < numStates; i++) {
            if (i > 0) {
                s += ", ";
            }
            s += i + ": ";
            s += "[";
            int n = getNumChoices(i);
            for (int j = 0; j < n; j++) {
                if (j > 0) {
                    s += ",";
                }
                Object o = mdp.getAction(i, j);
                if (o != null) {
                    s += o + ":";
                }
                s += mdp.trans.get(i).get(j);
                s += ", Radius: ";
                s += radii.get(i).get(j);
            }
            s += "]";
        }
        s += " ]\n";
        return s;
    }
}