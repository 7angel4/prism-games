package learning;

import explicit.CSGSimple;
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

public class Experiment
{
    public enum CaseStudy {
        VERY_SIMPLE,
        SIMPLE,
        SAFE_RISKY
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

        public String del() {
            return null;
        }
    }


    public CaseStudy model;
    public String modelFile;
    public String propertiesFile;
    public int propertyIndex;

    public Values parameterValues = new Values();
    public String solverString = "";

    public double epsilon = 0.5;
    public double confidence = 0.05;
    public double rMax = 1.0;

    public Experiment(CaseStudy model) {
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

    public void setSolverString(String solverString) {
        this.solverString = solverString;
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
//        System.out.println("finiteHorizon = " + finiteHorizon + ", propertyHorizon = " + propertyHorizon);
        // TODO: check if the fallback horizon is sufficient for convergence of value iteration (currently just a heuristic)
        return new PacRunSpec(trueGame, pf, prop, epsilon, confidence, rMax, propertyHorizon, finiteHorizon, solverString, zeroSum);
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


    public Experiment setModel(CaseStudy model) {
        this.model = model;
        this.parameterValues = new Values();
        this.confidence = 0.1;

        switch (model) {
            case VERY_SIMPLE -> {
                this.modelFile = "./prism-examples/csgs/learning/very_simple.prism";
                this.propertiesFile = "./prism-examples/csgs/learning/very_simple.props";
                this.propertyIndex = 4;

                this.epsilon = 0.5;
                this.rMax = 1.0;
            }
            case SIMPLE -> {
                this.modelFile = "./prism-examples/csgs/learning/simple.prism";
                this.propertiesFile = "./prism-examples/csgs/learning/simple.props";
                this.propertyIndex = 2;

                this.epsilon = 0.5;
                this.rMax = 1.0;
            }
            case SAFE_RISKY -> {
                this.modelFile = "./prism-examples/csgs/learning/safe_risky.prism";
                this.propertiesFile = "./prism-examples/csgs/learning/safe_risky.props";
                this.propertyIndex = 2;

                this.epsilon = 0.5;
                this.rMax = 10.0;
            }
        }
        return this;
    }


}