package learning;

import explicit.CSGSimple;
import explicit.StateModelChecker;
import explicit.StateValues;
import parser.Values;
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

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;
import java.util.BitSet;

public class Experiment
{
    public enum CASE_STUDY {
        VERY_SIMPLE,
        SIMPLE,
        RPS3,
        MAC2,
        MEDIUM_ACCESS2,
        TINY_ALOHA,
        ALOHA,
        ROBOT_COORD
    }

    public static final class PacRunSpec {
        public final CSGSimple<Double> trueGame;
        public final PropertiesFile propertiesFile;
        public final Property property;
        public final boolean zeroSum;

        public final double epsilon;
        public final double confidence;
        public final double rMax;

        public final int horizon;
        public final boolean finiteHorizon;
        public final String solverString;

        public PacRunSpec(
                CSGSimple<Double> trueGame,
                PropertiesFile propertiesFile,
                Property property,
                double epsilon,
                double confidence,
                double rMax,
                int horizon,
                boolean finiteHorizon,
                String solverString,
                boolean zeroSum
        ) {
            this.trueGame = trueGame;
            this.propertiesFile = propertiesFile;
            this.property = property;
            this.epsilon = epsilon;
            this.confidence = confidence;
            this.rMax = rMax;
            this.horizon = horizon;
            this.finiteHorizon = finiteHorizon;
            this.solverString = solverString;
            this.zeroSum = zeroSum;
        }
    }

    public CASE_STUDY model;
    public String modelFile;
    public String propertiesFile;
    public int propertyIndex;

    public Values parameterValues = new Values();
    public String solverString = "";

    public double epsilon = 0.5;
    public double confidence = 0.05;
    public double rMax = 1.0;
    public int horizon = 8;

    public Experiment(CASE_STUDY model) {
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

    public Experiment setSolverString(String solverString) {
        this.solverString = solverString;
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

    public PacRunSpec buildPacRunSpec(Prism prism) throws PrismException, FileNotFoundException {
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

        // Merge experiment constants into the property constant environment.
        // This lets a parameter like k be taken from the experiment, or from the props file.
        applyExperimentConstantsToPropertiesFile(pf);

        prism.buildModelIfRequired();
        @SuppressWarnings("unchecked")
        CSGSimple<Double> trueGame = (CSGSimple<Double>) prism.getBuiltModelExplicit();

//        validateSupportedProperty(prop, trueGame, pf, prism);
        boolean zeroSum = isZeroSumProperty(prop);
        int propertyHorizon = derivePropertyHorizon(prop, pf);
        boolean finiteHorizon = propertyHorizon >= 0;
        // TODO: check if the fallback horizon is sufficient for convergence of value iteration (currently just a heuristic)
       horizon = finiteHorizon ? propertyHorizon : trueGame.getNumStates() * 2;

        return new PacRunSpec(trueGame, pf, prop, epsilon, confidence, rMax, horizon, finiteHorizon, solverString, zeroSum);
    }

    private boolean isZeroSumProperty(Property prop) {
        ExpressionStrategy stratExpr = findFirstStrategyExpression(prop.getExpression());
        if (stratExpr == null) return false;

        Expression inner = stripParentheses(stratExpr.getOperand(0));
        if (!(inner instanceof ExpressionMultiNash multi)) {
            return true;
        }
        return multi.getOperands().size() == 1;
    }

    private void applyExperimentConstantsToPropertiesFile(PropertiesFile pf) {
        if (pf == null) return;

        Values pfConstants = pf.getConstantValues();
        if (pfConstants == null) return;

        // Copy experiment parameters into the properties-file constant environment.
        // Existing values with the same name are overwritten.
        pfConstants.setValues(parameterValues);
    }

    private int derivePropertyHorizon(Property prop, PropertiesFile pf)
            throws PrismException {

        ExpressionStrategy stratExpr = findFirstStrategyExpression(prop.getExpression());
        if (stratExpr == null)
            throw new PrismException("No strategy expression found");

        Expression inner = stripParentheses(stratExpr.getOperand(0));

        // ===== ZERO-SUM (single objective) =====
        if (!(inner instanceof ExpressionMultiNash multi)) {
            if (!(inner instanceof ExpressionQuant q)) {
                throw new PrismException("Expected quantifier expression");
            }

            if (q instanceof ExpressionMultiNashProb probQ) {
                Expression path = Expression.convertSimplePathFormulaToCanonicalForm(probQ.getExpression());
                if (path instanceof ExpressionTemporal t) {
                    return deriveTemporalHorizon(t, pf);
                }
            } else if (q instanceof ExpressionMultiNashReward) {
                return deriveRewardHorizon(q, pf);
            }

            return -1;
        }

        // ===== GENERAL-SUM =====
        int horizon = -1;

        for (ExpressionQuant q : multi.getOperands()) {

            if (q instanceof ExpressionMultiNashProb probQ) {
                Expression path = Expression.convertSimplePathFormulaToCanonicalForm(probQ.getExpression());
                if (!(path instanceof ExpressionTemporal t))
                    throw new PrismException("Expected temporal formula");

                int h = deriveTemporalHorizon(t, pf);
                if (h < 0) return -1;
                horizon = Math.max(horizon, h);

            } else if (q instanceof ExpressionMultiNashReward) {
                int h = deriveRewardHorizon(q, pf);
                if (h < 0) return -1;
                horizon = Math.max(horizon, h);
            }
        }

        return horizon;
    }

    private int deriveTemporalHorizon(ExpressionTemporal temporal, PropertiesFile pf) throws PrismException, PrismLangException {
        switch (temporal.getOperator()) {
            case ExpressionTemporal.P_F, ExpressionTemporal.P_U -> {
                if (!temporal.hasBounds()) {
                    return -1;
                }
                IntegerBound b = IntegerBound.fromExpressionTemporal(temporal, pf.getConstantValues(), true);
                return extractUpperBound(b);
            }
            default -> throw new PrismException("Unsupported temporal operator: " + temporal.getOperatorSymbol());
        }
    }

    private int deriveRewardHorizon(ExpressionQuant q, PropertiesFile pf) throws PrismException, PrismLangException {
        String text = q.toString();

        int open = text.indexOf('[');
        int close = text.indexOf(']', open + 1);
        if (open < 0 || close < 0 || close <= open + 1) {
            return -1;
        }

        String inside = text.substring(open + 1, close).replace(" ", "").replace("\t", "");
        int boundIdx = inside.indexOf("C<=");
        if (boundIdx < 0) {
            return -1; // unbounded reward property
        }

        String boundExpr = inside.substring(boundIdx + 3).trim();
        if (boundExpr.isEmpty()) {
            throw new PrismException("Empty cumulative reward bound in property: " + q);
        }

        Integer bound = resolveIntegerConstant(boundExpr, pf);
        if (bound == null) {
            throw new PrismException("Could not resolve numeric expression: " + boundExpr);
        }

        if (bound < 0) {
            throw new PrismException("Reward horizon must be non-negative: " + boundExpr);
        }

        return bound;
    }

    private Integer resolveIntegerConstant(String nameOrNumber, PropertiesFile pf) throws PrismLangException {
        String s = nameOrNumber.trim();

        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // not a literal
        }

        Object v = resolveValueByName(s, parameterValues);
        if (v == null && pf != null && pf.getConstantValues() != null) {
            v = resolveValueByName(s, pf.getConstantValues());
        }

        if (v == null) {
            return null;
        }
        return coerceToInt(v);
    }

    private Object resolveValueByName(String name, Values values) throws PrismLangException {
        if (values == null || !values.contains(name)) {
            return null;
        }
        return values.getValueOf(name);
    }

    private Integer coerceToInt(Object v) {
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l) {
            return Math.toIntExact(l);
        }
        if (v instanceof Double d) {
            return (int) Math.round(d);
        }
        if (v instanceof Float f) {
            return Math.round(f);
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int extractUpperBound(IntegerBound bound) throws PrismException {
        String[] candidates = {
                "getUpperBound",
                "getUpper",
                "getBound",
                "getValue",
                "getUpperValue",
                "getIntBound"
        };

        for (String name : candidates) {
            try {
                Method m = bound.getClass().getMethod(name);
                Object value = m.invoke(bound);
                if (value instanceof Number n) {
                    return n.intValue();
                }
                if (value instanceof String s) {
                    return Integer.parseInt(s.trim());
                }
            } catch (ReflectiveOperationException ignored) {
                // try next
            }
        }

        Integer parsed = parseFirstInteger(bound.toString());
        if (parsed != null) {
            return parsed;
        }

        throw new PrismException("Could not extract an upper bound from IntegerBound: " + bound);
    }

    private Integer parseFirstInteger(String s) {
        if (s == null) {
            return null;
        }

        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '-') {
            i++;
        }
        if (i >= s.length()) {
            return null;
        }

        int j = i + 1;
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }

        try {
            return Integer.parseInt(s.substring(i, j));
        } catch (NumberFormatException e) {
            return null;
        }
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
        Expression cur = expr;
        while (cur instanceof ExpressionUnaryOp && Expression.isParenth(cur)) {
            cur = ((ExpressionUnaryOp) cur).getOperand();
        }
        return cur;
    }


    public Experiment setModel(CASE_STUDY model) {
        this.model = model;
        this.parameterValues = new Values();
        this.confidence = 0.1;

        switch (model) {
            case VERY_SIMPLE -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/very_simple_rew.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/very_simple.props";
                this.propertyIndex = 1;

                this.epsilon = 0.5;
                this.rMax = 1.0;
                this.horizon = 4;
            }
            case SIMPLE -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/simple.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/simple.props";
                this.propertyIndex = 2;

                this.epsilon = 0.5;
                this.rMax = 1.0;
                this.horizon = 1;
            }
            case RPS3 -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/rps3.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/rps3.props";
                this.propertyIndex = 1;

                addParameters("k", 2);
                this.epsilon = 0.5;
                this.rMax = 1.0;
            }
            case MEDIUM_ACCESS2 -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/medium_access2.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/medium_access2.props";
                this.propertyIndex = 1;

                addParameters("k1", 2, "k2", 2, "emax", 4, "q1", 0.95, "q2", 0.75);
                this.epsilon = 0.5;
                this.rMax = 1.0;
            }
            case MAC2 -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/medium_access_count2.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/medium_access_count2.props";
                this.propertyIndex = 1;

                addParameters("k", 2, "emax", 4, "smax", 2, "q1", 0.95, "q2", 0.75);
                this.epsilon = 0.5;
                this.rMax = 1.0;
            }
            case TINY_ALOHA -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/tiny_aloha3.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/tiny_aloha3.props";
                this.propertyIndex = 7;

                addParameters("D", 2, "q", 0.9, "bcmax", 1);

                this.epsilon = 0.5;
                this.rMax = 1.0;
                this.horizon = 2;
            }
            case ALOHA -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/aloha_backoff3.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/aloha_backoff3.props";
                this.propertyIndex = 7;

                addParameters("D", 8, "q", 0.9, "bcmax", 1);

                this.epsilon = 0.5;
                this.rMax = 1.0;
                this.horizon = 8;
            }
            case ROBOT_COORD -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/robot_coordination/robot_coordination2.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/robot_coordination/robot_coordination2.props";
                this.propertyIndex = 7;

                addParameters("l", 4, "q", 0.25);

                this.epsilon = 0.5;
                this.rMax = 1.0;
                this.horizon = 8;
            }
        }
        return this;
    }


//    private void validateSupportedProperty(
//            Property prop,
//            CSGSimple<Double> trueGame,
//            PropertiesFile pf,
//            Prism prism
//    ) throws PrismException {
//
//        ExpressionStrategy stratExpr = findFirstStrategyExpression(prop.getExpression());
//        if (stratExpr == null) {
//            throw new PrismException("Could not find an ExpressionStrategy inside property " + propertyIndex);
//        }
//
//        Expression inner = stripParentheses(stratExpr.getOperand(0));
//
//        // ================= MULTI-OBJECTIVE (general-sum) =================
//        if (inner instanceof ExpressionMultiNash multiNash) {
//            for (ExpressionQuant q : multiNash.getOperands()) {
//                validateSingleObjective(q, trueGame, pf, prism);
//            }
//            return;
//        }
//
//        // ================= SINGLE OBJECTIVE =================
//        // (zero-sum OR single-player OR anything not MultiNash)
//        validateSingleObjective(inner, trueGame, pf, prism);
//    }
//
//
//    private void validateSingleObjective(
//            Expression expr,
//            CSGSimple<Double> trueGame,
//            PropertiesFile pf,
//            Prism prism
//    ) throws PrismException {
//
//        // ---------- probabilistic objective ----------
//        if (expr instanceof ExpressionProb prob) {
//
//            Expression path = Expression.convertSimplePathFormulaToCanonicalForm(prob.getExpression());
//
//            if (!(path instanceof ExpressionTemporal temporal)) {
//                throw new PrismException("Expected temporal formula, got " + path.getClass().getSimpleName());
//            }
//
//            validateTemporalFormula(temporal, trueGame, pf, prism);
//        }
//    }
//
//    private void validateTemporalFormula(
//            ExpressionTemporal temporal,
//            CSGSimple<Double> trueGame,
//            PropertiesFile pf,
//            Prism prism
//    ) throws PrismException {
//
//        switch (temporal.getOperator()) {
//
//            case ExpressionTemporal.P_F -> {
//                evaluateStateFormulaToBitSet(prism, trueGame, pf, temporal.getOperand2());
//            }
//
//            case ExpressionTemporal.P_U -> {
//                evaluateStateFormulaToBitSet(prism, trueGame, pf, temporal.getOperand2());
//
//                Expression guard = temporal.getOperand1();
//                if (!Expression.isTrue(guard)) {
//                    evaluateStateFormulaToBitSet(prism, trueGame, pf, guard);
//                }
//            }
//
//            default -> throw new PrismException(
//                    "Unsupported temporal operator: " + temporal.getOperatorSymbol());
//        }
//    }
//
//    private BitSet evaluateStateFormulaToBitSet(
//            Prism prism,
//            CSGSimple<Double> trueGame,
//            PropertiesFile pf,
//            Expression stateFormula
//    ) throws PrismException {
//        StateModelChecker mc = StateModelChecker.createModelChecker(trueGame.getModelType(), prism);
//        mc.setModelCheckingInfo(prism.getModelInfo(), pf, prism.getRewardGenerator());
//        StateValues sv = mc.checkExpression(trueGame, stateFormula, null);
//        return (BitSet) sv.getBitSet().clone();
//    }

}