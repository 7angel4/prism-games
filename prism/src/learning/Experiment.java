package learning;

import explicit.CSGSimple;
import explicit.rewards.CSGRewards;
import parser.Values;
import parser.ast.Coalition;
import parser.ast.Expression;
import parser.ast.ExpressionMultiNash;
import parser.ast.ExpressionMultiNashProb;
import parser.ast.ExpressionMultiNashReward;
import parser.ast.ExpressionProb;
import parser.ast.ExpressionQuant;
import parser.ast.ExpressionReward;
import parser.ast.ExpressionStrategy;
import parser.ast.ExpressionTemporal;
import parser.ast.ExpressionUnaryOp;
import parser.ast.ModulesFile;
import parser.ast.Property;
import parser.ast.PropertiesFile;
import prism.IntegerBound;
import prism.Prism;
import prism.PrismException;
import prism.PrismLangException;
import prism.Result;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

@SuppressWarnings({"unchecked", "rawtypes"})
public class Experiment {

    public enum Model {
        TEST_CSG
    }

    public static final class PacRunSpec {
        public final CSGSimple<Double> trueGame;
        public final PACLearner.ObjectiveKind objectiveKind;
        public final List<Coalition> coalitions;
        public final List<ExpressionTemporal> exprs;
        public final List<CSGRewards<Double>> rewards;
        public final BitSet[] targets;
        public final BitSet[] remain;
        public final int[] bounds;
        public final int eqType;
        public final int crit;
        public final boolean min;
        public final double eps;
        public final double delta;
        public final double rMax;
        public final int horizon;

        public PacRunSpec(CSGSimple<Double> trueGame,
                          PACLearner.ObjectiveKind objectiveKind,
                          List<Coalition> coalitions,
                          List<ExpressionTemporal> exprs,
                          List<CSGRewards<Double>> rewards,
                          BitSet[] targets,
                          BitSet[] remain,
                          int[] bounds,
                          int eqType,
                          int crit,
                          boolean min,
                          double eps,
                          double delta,
                          double rMax,
                          int horizon) {
            this.trueGame = trueGame;
            this.objectiveKind = objectiveKind;
            this.coalitions = coalitions;
            this.exprs = exprs;
            this.rewards = rewards;
            this.targets = targets;
            this.remain = remain;
            this.bounds = bounds;
            this.eqType = eqType;
            this.crit = crit;
            this.min = min;
            this.eps = eps;
            this.delta = delta;
            this.rMax = rMax;
            this.horizon = horizon;
        }
    }

    public Model model;

    public String modelFile;
    public String propertiesFile;
    public int propertyIndex; // 1-based, like -prop 7

    public Values parameterValues = new Values();
    public double pacEps;
    public double pacDelta;
    public double rMax;
    public int horizon;

    public int eqType;
    public int crit;
    public boolean min;

    public PACLearner.ObjectiveKind objectiveKind;

    public Experiment(Model model) {
        setModel(model);
    }

    public Experiment setValues(Values values) {
        this.parameterValues = values;
        return this;
    }

    public Experiment setSingleValue(String name, Object value) {
        this.parameterValues.setValue(name, value);
        return this;
    }

    public Experiment setModel(Model model) {
        this.model = model;
        switch (model) {
            case TEST_CSG -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/aloha_backoff3.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/aloha_backoff3.props";
                this.propertyIndex = 7;

                parameterValues = new Values();
                addParameters(
                        "D", 8,
                        "q", 0.9,
                        "eps", 1.0 / 257.0,
                        "bcmax", 1
                );

                this.pacEps = 1.0 / 257.0;
                this.pacDelta = 0.05;
                this.rMax = 1.0;
                this.horizon = 8;

                this.eqType = 0;
                this.crit = 0;
                this.min = false;

                this.objectiveKind = PACLearner.ObjectiveKind.PROB_REACH;
            }
        }
        return this;
    }

    private void addParameters(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Parameter name/value pairs must be even.");
        }
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            String name = (String) nameValuePairs[i];
            this.parameterValues.addValue(name, nameValuePairs[i + 1]);
        }
    }

    public PacRunSpec buildPacRunSpec(Prism prism)
            throws PrismException, PrismLangException, FileNotFoundException {

        File mfFile = Prism.resolveFile(modelFile);
        File pfFile = Prism.resolveFile(propertiesFile);

        ModulesFile mf = prism.parseModelFile(mfFile);
        prism.loadPRISMModel(mf);
        prism.setPRISMModelConstants(parameterValues);

        PropertiesFile pf = prism.parsePropertiesFile(mf, pfFile);

        int idx = propertyIndex - 1;
        if (idx < 0 || idx >= pf.getNumProperties()) {
            throw new PrismException("Property index out of range: " + propertyIndex);
        }

        Property prop = pf.getPropertyObject(idx);
        Expression top = prop.getExpression();

        ExpressionStrategy stratExpr = findFirstStrategyExpression(top);
        if (stratExpr == null) {
            throw new PrismException("Could not find a strategy expression inside property " + propertyIndex);
        }

        List<Coalition> coalitions = stratExpr.getCoalitions() == null
                ? Collections.emptyList()
                : new ArrayList<>(stratExpr.getCoalitions());

        Expression inner = stripParentheses(stratExpr.getOperand(0));
        if (!(inner instanceof ExpressionMultiNash multiNash)) {
            throw new PrismException("Expected an ExpressionMultiNash inside the strategy expression, got: " + inner.getClass().getSimpleName());
        }

        prism.buildModelIfRequired();
        @SuppressWarnings("unchecked")
        CSGSimple<Double> trueGame = (CSGSimple<Double>) prism.getBuiltModelExplicit();

        List<ExpressionTemporal> exprs = new ArrayList<>();
        List<CSGRewards<Double>> rewards = new ArrayList<>();

        List<ExpressionQuant> formulae = multiNash.getOperands();

        BitSet[] targets = new BitSet[formulae.size()];
        BitSet[] remain = new BitSet[formulae.size()];
        int[] bounds = new int[formulae.size()];
        Arrays.fill(bounds, -1);

        boolean hasBoundedUntil = false;
        boolean hasRewards = false;

        for (int p = 0; p < formulae.size(); p++) {
            ExpressionQuant q = formulae.get(p);

            if (q instanceof ExpressionMultiNashProb probQ) {
                Expression path = Expression.convertSimplePathFormulaToCanonicalForm(probQ.getExpression());
                if (!(path instanceof ExpressionTemporal temporal)) {
                    throw new PrismException("Expected a temporal path formula, got: " + path.getClass().getSimpleName());
                }

                exprs.add(temporal);

                switch (temporal.getOperator()) {
                    case ExpressionTemporal.P_F -> {
                        // Unbounded reachability: target is the operand of F
                        targets[p] = evaluateStateFormulaToBitSet(prism, pf, temporal.getOperand2());
                    }
                    case ExpressionTemporal.P_U -> {
                        // Until: target is operand2, remain is operand1 unless it is true
                        targets[p] = evaluateStateFormulaToBitSet(prism, pf, temporal.getOperand2());

                        if (temporal.hasBounds()) {
                            IntegerBound b = IntegerBound.fromExpressionTemporal(temporal, pf.getConstantValues(), true);
                            if (!b.hasUpperBound()) {
                                throw new PrismException("Only upper-bounded until is supported here.");
                            }
                            bounds[p] = b.getHighestInteger();
                            hasBoundedUntil = true;
                        }
                    }
                    default -> throw new PrismException("Unsupported temporal operator inside multi-objective property: " + temporal.getOperatorSymbol());
                }
            } else if (q instanceof ExpressionMultiNashReward rewQ) {
                hasRewards = true;
                throw new PrismException("Reward multi-objective properties are not wired into this test harness yet.");
            } else {
                throw new PrismException("Unsupported multi-objective component: " + q.getClass().getSimpleName());
            }
        }

        PACLearner.ObjectiveKind kind;
        if (hasRewards) {
            kind = hasBoundedUntil ? PACLearner.ObjectiveKind.REW_BOUNDED : PACLearner.ObjectiveKind.REW_REACH;
        } else {
            kind = hasBoundedUntil ? PACLearner.ObjectiveKind.PROB_REACH_BOUNDED : PACLearner.ObjectiveKind.PROB_REACH;
        }

        // For pure reachability properties, PRISM typically uses remain = null.
        // If you have a genuine until-guard, keep the remain array.
        remain = buildRemain(trueGame);

        boolean min = false;

        return new PacRunSpec(
                trueGame,
                kind,
                coalitions,
                exprs,
                rewards,
                targets,
                remain,
                bounds,
                eqType,
                crit,
                min,
                pacEps,
                pacDelta,
                rMax,
                horizon
        );
    }

    private ExpressionStrategy findFirstStrategyExpression(Expression expr) {
        if (expr == null) {
            return null;
        }

        if (expr instanceof ExpressionStrategy s) {
            return s;
        }

        if (expr instanceof ExpressionProb p) {
            return findFirstStrategyExpression(p.getExpression());
        }

        if (expr instanceof ExpressionReward r) {
            return findFirstStrategyExpression(r.getExpression());
        }

        if (expr instanceof ExpressionTemporal t) {
            ExpressionStrategy found = findFirstStrategyExpression(t.getOperand1());
            if (found != null) {
                return found;
            }
            return findFirstStrategyExpression(t.getOperand2());
        }

        if (expr instanceof ExpressionUnaryOp u) {
            return findFirstStrategyExpression(u.getOperand());
        }

        return null;
    }

    private Expression stripParentheses(Expression expr) {
        Expression current = expr;
        while (current instanceof ExpressionUnaryOp u && Expression.isParenth(current)) {
            current = u.getOperand();
        }
        return current;
    }

    private BitSet evaluateStateFormulaToBitSet(Prism prism, PropertiesFile pf, Expression stateFormula)
            throws PrismException, PrismLangException {

        Result res = prism.modelCheck(pf, stateFormula);
        Object raw = res.getResult();

        if (raw instanceof explicit.StateValues sv) {
            return (BitSet) sv.getBitSet().clone();
        }

        if (raw instanceof BitSet bs) {
            return (BitSet) bs.clone();
        }

        if (raw instanceof Boolean b) {
            BitSet bs = new BitSet();
            if (b) {
                bs.set(0, 1);
            }
            return bs;
        }

        throw new PrismException("Cannot extract a BitSet from model-checking result type: " + raw.getClass().getName());
    }

    private BitSet[] buildRemain(CSGSimple<Double> trueGame) {
        int n = trueGame.getNumStates();
        BitSet[] remain = new BitSet[2];
        remain[0] = new BitSet(n);
        remain[1] = new BitSet(n);
        remain[0].set(0, n);
        remain[1].set(0, n);
        return remain;
    }
}