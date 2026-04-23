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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Experiment
{
    public enum Model {
        VERY_SIMPLE,
        TINY_ALOHA,
        ALOHA,
        ROBOT_COORD
    }

    public static final class PacRunSpec {
        public final CSGSimple<Double> trueGame;
        public final PropertiesFile propertiesFile;
        public final Property property;

        public final double epsilon;
        public final double delta;
        public final double rMax;

        /**
         * Bounded horizon extracted from the property, or -1 if unbounded.
         */
        public final int propertyHorizon;

        /**
         * Rollout cap used only for simulation / exploration.
         */
        public final int rolloutCap;

        public final boolean finiteHorizon;
        public final String solverString;

        /**
         * Kept for compatibility with existing code.
         */
        public final int horizon;

        public PacRunSpec(
                CSGSimple<Double> trueGame,
                PropertiesFile propertiesFile,
                Property property,
                double epsilon,
                double delta,
                double rMax,
                int propertyHorizon,
                int rolloutCap,
                String solverString
        ) {
            this.trueGame = trueGame;
            this.propertiesFile = propertiesFile;
            this.property = property;
            this.delta = delta;
            this.rMax = rMax;
            this.epsilon = epsilon;
            this.propertyHorizon = propertyHorizon;
            this.rolloutCap = rolloutCap;
            this.finiteHorizon = propertyHorizon >= 0;
            this.horizon = rolloutCap;
            this.solverString = solverString;
        }
    }

    /**
     * Raw configuration only. No duplicated parsed runtime fields are kept here.
     */
    public Model model;
    public String modelFile;
    public String propertiesFile;
    public int propertyIndex; // 1-based

    public Values parameterValues = new Values();
    public String solverString = "";

    public double epsilon = 0.5;
    public double confidence = 0.05;
    public double rMax = 1.0;
    public int horizon = 8; // fallback rollout cap

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
                this.propertyIndex = 1;

                parameterValues = new Values();
                this.epsilon = 0.5;
                this.confidence = 0.1;
                this.rMax = 1.0;
                this.horizon = 2;
            }
            case TINY_ALOHA -> {
                this.modelFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/tiny_aloha3.prism";
                this.propertiesFile = "/Users/angel/Desktop/prism-games/prism-examples/csgs/aloha/tiny_aloha3.props";
                this.propertyIndex = 7;

                parameterValues = new Values();
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

                parameterValues = new Values();
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

                parameterValues = new Values();
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

        prism.buildModelIfRequired();
        @SuppressWarnings("unchecked")
        CSGSimple<Double> trueGame = (CSGSimple<Double>) prism.getBuiltModelExplicit();

        validateSupportedProperty(prop, trueGame, pf, prism);

        int propertyHorizon = derivePropertyHorizon(prop, pf, horizon);
        int rolloutCap = (propertyHorizon >= 0) ? propertyHorizon : horizon;

        return new PacRunSpec(trueGame, pf, prop, epsilon, confidence, rMax, propertyHorizon, rolloutCap, solverString
        );
    }

    private int derivePropertyHorizon(Property prop, PropertiesFile pf, int fallbackHorizon) throws PrismException {
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
                int thisHorizon = deriveRewardHorizonFromText(q.toString(), pf, fallbackHorizon);
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

    private int deriveTemporalHorizon(ExpressionTemporal temporal, PropertiesFile pf) throws PrismException {
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

    private int deriveRewardHorizonFromText(String text, PropertiesFile pf, int fallbackHorizon) throws PrismException {
        String compact = text.replaceAll("\\s+", "");

        Matcher m = Pattern.compile("\\[C<=([^\\]]+)\\]").matcher(compact);
        if (!m.find()) {
            return -1; // unbounded total reward / reachability reward
        }

        String boundExpr = m.group(1);

        Double bound = tryResolveNumericExpression(boundExpr, pf);
        if (bound == null) {
            if (fallbackHorizon > 0) {
                return fallbackHorizon;
            }
            throw new PrismException("Could not resolve numeric expression: " + boundExpr);
        }

        if (bound < 0.0) {
            throw new PrismException("Reward horizon must be non-negative: " + boundExpr);
        }

        return (int) Math.ceil(bound);
    }

    private Double tryResolveNumericExpression(String expr, PropertiesFile pf) {
        String s = expr.trim();

        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {
            // fall through
        }

        Object constants = pf.getConstantValues();
        String[] methods = {
                "getValue",
                "getDoubleValue",
                "getIntValue",
                "getIntegerValue",
                "get"
        };

        for (String methodName : methods) {
            try {
                Method m = constants.getClass().getMethod(methodName, String.class);
                Object value = m.invoke(constants, s);
                if (value instanceof Number n) {
                    return n.doubleValue();
                }
                if (value instanceof String str) {
                    return Double.parseDouble(str.trim());
                }
            } catch (ReflectiveOperationException | NumberFormatException ignored) {
                // try next
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

        Matcher matcher = Pattern.compile("-?\\d+").matcher(bound.toString());
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }

        throw new PrismException("Could not extract an upper bound from IntegerBound: " + bound);
    }

    private void validateSupportedProperty(
            Property prop,
            CSGSimple<Double> trueGame,
            PropertiesFile pf,
            Prism prism
    ) throws PrismException {

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
                    case ExpressionTemporal.P_F -> {
                        evaluateStateFormulaToBitSet(prism, trueGame, pf, temporal.getOperand2());
                    }
                    case ExpressionTemporal.P_U -> {
                        evaluateStateFormulaToBitSet(prism, trueGame, pf, temporal.getOperand2());
                        Expression guard = temporal.getOperand1();
                        if (!Expression.isTrue(guard)) {
                            evaluateStateFormulaToBitSet(prism, trueGame, pf, guard);
                        }

                        if (temporal.hasBounds()) {
                            IntegerBound b = IntegerBound.fromExpressionTemporal(temporal, pf.getConstantValues(), true);
                            extractUpperBound(b);
                        }
                    }
                    default -> throw new PrismException("Unsupported temporal operator: " + temporal.getOperatorSymbol());
                }

            } else if (q instanceof ExpressionMultiNashReward) {
                // Accepted.
                continue;

            } else {
                throw new PrismException("Unsupported multi-objective term: " + q.getClass().getSimpleName());
            }
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