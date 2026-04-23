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
    public enum Model {
        VERY_SIMPLE,
        TINY_ALOHA,
        ALOHA,
        ROBOT_COORD
    }

    public static final class PacRunSpec {
        public final Model model;
        public final String modelFile;
        public final String propertiesFilePath;
        public final int propertyIndex;

        public final CSGSimple<Double> trueGame;
        public final PropertiesFile propertiesFile;
        public final Property property;

        public final double epsilon;
        public final double confidence;
        public final double rMax;

        public final int propertyHorizon;
        public final int rolloutCap;
        public final boolean finiteHorizon;
        public final String solverString;

        public final int horizon;

        public PacRunSpec(
                Model model,
                String modelFile,
                String propertiesFilePath,
                int propertyIndex,
                CSGSimple<Double> trueGame,
                PropertiesFile propertiesFile,
                Property property,
                double epsilon,
                double confidence,
                double rMax,
                int propertyHorizon,
                int rolloutCap,
                String solverString
        ) {
            this.model = model;
            this.modelFile = modelFile;
            this.propertiesFilePath = propertiesFilePath;
            this.propertyIndex = propertyIndex;
            this.trueGame = trueGame;
            this.propertiesFile = propertiesFile;
            this.property = property;
            this.epsilon = epsilon;
            this.confidence = confidence;
            this.rMax = rMax;
            this.propertyHorizon = propertyHorizon;
            this.rolloutCap = rolloutCap;
            this.finiteHorizon = propertyHorizon >= 0;
            this.horizon = rolloutCap;
            this.solverString = solverString;
        }
    }

    public Model model;
    public String modelFile;
    public String propertiesFile;
    public int propertyIndex;

    public Values parameterValues = new Values();
    public String solverString = "";

    public double epsilon = 0.5;
    public double confidence = 0.05;
    public double rMax = 1.0;
    public int horizon = 8;

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

    public Experiment setSolverString(String solverString) {
        this.solverString = solverString;
        return this;
    }

    public Experiment setModel(Model model) {
        this.model = model;

        switch (model) {
            case VERY_SIMPLE -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/very_simple_rew.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/simple/very_simple.props";
                this.propertyIndex = 4;

                this.parameterValues = new Values();
                this.epsilon = 0.5;
                this.confidence = 0.1;
                this.rMax = 1.0;
                this.horizon = 1;
            }
            case TINY_ALOHA -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/tiny_aloha3.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/tiny_aloha3.props";
                this.propertyIndex = 7;

                this.parameterValues = new Values();
                addParameters("D", 2, "q", 0.9, "bcmax", 1);

                this.epsilon = 0.5;
                this.confidence = 0.1;
                this.rMax = 1.0;
                this.horizon = 2;
            }
            case ALOHA -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/aloha_backoff3.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/aloha_backoff3.props";
                this.propertyIndex = 7;

                this.parameterValues = new Values();
                addParameters("D", 8, "q", 0.9, "bcmax", 1);

                this.epsilon = 0.5;
                this.confidence = 0.05;
                this.rMax = 1.0;
                this.horizon = 8;
            }
            case ROBOT_COORD -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/robot_coordination/robot_coordination2.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/robot_coordination/robot_coordination2.props";
                this.propertyIndex = 7;

                this.parameterValues = new Values();
                addParameters("l", 4, "q", 0.25);

                this.epsilon = 0.5;
                this.confidence = 0.05;
                this.rMax = 1.0;
                this.horizon = 8;
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

    public PacRunSpec buildPacRunSpec(Prism prism) throws PrismException, FileNotFoundException, PrismLangException {
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

        validateSupportedProperty(prop, trueGame, pf, prism);

        int propertyHorizon = derivePropertyHorizon(prop, pf);
        int rolloutCap = (propertyHorizon >= 0) ? propertyHorizon : horizon;

        return new PacRunSpec(
                model,
                modelFile,
                propertiesFile,
                propertyIndex,
                trueGame,
                pf,
                prop,
                epsilon,
                confidence,
                rMax,
                propertyHorizon,
                rolloutCap,
                solverString
        );
    }

    private void applyExperimentConstantsToPropertiesFile(PropertiesFile pf) throws PrismLangException {
        if (pf == null) {
            return;
        }

        Values pfConstants = pf.getConstantValues();
        if (pfConstants == null) {
            return;
        }

        // Copy experiment parameters into the properties-file constant environment.
        // Existing values with the same name are overwritten.
        pfConstants.setValues(parameterValues);
    }

    private int derivePropertyHorizon(Property prop, PropertiesFile pf) throws PrismException, PrismLangException {
        ExpressionStrategy stratExpr = findFirstStrategyExpression(prop.getExpression());
        if (stratExpr == null) {
            throw new PrismException("Could not find an ExpressionStrategy inside property " + propertyIndex);
        }

        Expression inner = stripParentheses(stratExpr.getOperand(0));
        if (!(inner instanceof ExpressionMultiNash multiNash)) {
            throw new PrismException("Expected ExpressionMultiNash inside strategy expression, got " + inner.getClass().getSimpleName());
        }

        int horizon = -1;
        boolean sawAnyObjective = false;

        for (ExpressionQuant q : multiNash.getOperands()) {
            sawAnyObjective = true;

            if (q instanceof ExpressionMultiNashProb probQ) {
                Expression path = Expression.convertSimplePathFormulaToCanonicalForm(probQ.getExpression());
                if (!(path instanceof ExpressionTemporal temporal)) {
                    throw new PrismException("Expected a temporal formula, got " + path.getClass().getSimpleName());
                }

                int thisHorizon = deriveTemporalHorizon(temporal, pf);
                if (thisHorizon < 0) {
                    return -1;
                }
                horizon = Math.max(horizon, thisHorizon);

            } else if (q instanceof ExpressionMultiNashReward) {
                int thisHorizon = deriveRewardHorizon(q, pf);
                if (thisHorizon < 0) {
                    return -1;
                }
                horizon = Math.max(horizon, thisHorizon);

            } else {
                throw new PrismException("Unsupported multi-objective term: " + q.getClass().getSimpleName());
            }
        }

        return sawAnyObjective ? horizon : -1;
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

    private void validateSupportedProperty(
            Property prop,
            CSGSimple<Double> trueGame,
            PropertiesFile pf,
            Prism prism
    ) throws PrismException, PrismLangException {

        ExpressionStrategy stratExpr = findFirstStrategyExpression(prop.getExpression());
        if (stratExpr == null) {
            throw new PrismException("Could not find an ExpressionStrategy inside property " + propertyIndex);
        }

        Expression inner = stripParentheses(stratExpr.getOperand(0));
        if (!(inner instanceof ExpressionMultiNash multiNash)) {
            throw new PrismException("Expected ExpressionMultiNash inside strategy expression, got " + inner.getClass().getSimpleName());
        }

        for (ExpressionQuant q : multiNash.getOperands()) {
            if (q instanceof ExpressionMultiNashProb probQ) {
                Expression path = Expression.convertSimplePathFormulaToCanonicalForm(probQ.getExpression());
                if (!(path instanceof ExpressionTemporal temporal)) {
                    throw new PrismException("Expected a temporal formula, got " + path.getClass().getSimpleName());
                }

                switch (temporal.getOperator()) {
                    case ExpressionTemporal.P_F -> evaluateStateFormulaToBitSet(prism, trueGame, pf, temporal.getOperand2());
                    case ExpressionTemporal.P_U -> {
                        evaluateStateFormulaToBitSet(prism, trueGame, pf, temporal.getOperand2());
                        Expression guard = temporal.getOperand1();
                        if (!Expression.isTrue(guard)) {
                            evaluateStateFormulaToBitSet(prism, trueGame, pf, guard);
                        }
                    }
                    default -> throw new PrismException("Unsupported temporal operator: " + temporal.getOperatorSymbol());
                }

            } else if (q instanceof ExpressionMultiNashReward) {
                continue;
            } else {
                throw new PrismException("Unsupported multi-objective term: " + q.getClass().getSimpleName());
            }
        }
    }

    private BitSet evaluateStateFormulaToBitSet(
            Prism prism,
            CSGSimple<Double> trueGame,
            PropertiesFile pf,
            Expression stateFormula
    ) throws PrismException, PrismLangException {
        StateModelChecker mc = StateModelChecker.createModelChecker(trueGame.getModelType(), prism);
        mc.setModelCheckingInfo(prism.getModelInfo(), pf, prism.getRewardGenerator());
        StateValues sv = mc.checkExpression(trueGame, stateFormula, null);
        return (BitSet) sv.getBitSet().clone();
    }
}