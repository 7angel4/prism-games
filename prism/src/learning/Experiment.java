package learning;

import explicit.CSGSimple;
import explicit.MinMax;
import parser.Values;
import parser.ast.*;
import prism.*;

import java.io.File;
import java.io.FileNotFoundException;

public class Experiment {

    public enum CaseStudy {
//        VERY_SIMPLE,
        SAFE_RISKY,
        TRAFFIC_MERGE,
        NO_NE,
        TEST
    }

    public static final class PacRunSpec {
        public final CSGSimple<Double> trueGame;
        public final PropertiesFile propertiesFile;

        public final double epsilon; // if negative, will be derived from rMax
        public final double confidence;
        public final double rMax;

        public final Property property;
        public final boolean zeroSum;
        public final MinMax minMax;
        public final int horizon;
        public final boolean finiteHorizon;
        public final boolean useRewards;
        public final String solver;

        public final int maxNumEpisodes;

        public PacRunSpec(
                CSGSimple<Double> trueGame,
                PropertiesFile propertiesFile,
                Property property,
                double epsilon,
                double confidence,
                double rMax,
                int horizon,
                boolean finiteHorizon,
                String solver,
                boolean zeroSum,
                boolean useRewards,
                MinMax minMax,
                int maxNumEpisodes
        ) {
            this.trueGame = trueGame;
            this.propertiesFile = propertiesFile;
            this.property = property;
            this.epsilon = epsilon;
            this.confidence = confidence;
            this.rMax = rMax;
            this.horizon = horizon;
            this.finiteHorizon = finiteHorizon;
            this.solver = solver;
            this.zeroSum = zeroSum;
            this.useRewards = useRewards;
            this.minMax = minMax;
            this.maxNumEpisodes = maxNumEpisodes;
        }
    }

    public CaseStudy model;
    public String modelFile;
    public String propertiesFile;
    public int propertyIndex;

    public Values parameterValues = new Values();
    public String solverString = "";

    public double epsilon = -1.0; // if negative, will be derived from rMax
    public double confidence = 0.05;
    public int maxNumEpisodes = 1000;
    public boolean robustnessExperiment = false;

    public Experiment(CaseStudy model) {
        setModel(model);
    }

    public void setValues(Values values) {
        this.parameterValues = values;
    }

    public void setSingleValue(String name, Object value) {
        this.parameterValues.setValue(name, value);
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
    public void setSolverString(String solverString) {
        this.solverString = solverString;
    }

    public PacRunSpec buildPacRunSpec(Prism prism) throws PrismException, FileNotFoundException {

        File mfFile = Prism.resolveFile(modelFile);
        File pfFile = Prism.resolveFile(propertiesFile);

        ModulesFile mf = prism.parseModelFile(mfFile);
        prism.loadPRISMModel(mf);
        prism.setPRISMModelConstants(parameterValues);

        PropertiesFile pf = prism.parsePropertiesFile(mf, pfFile);

        // merge experiment constants into properties
        pf.setSomeUndefinedConstants(parameterValues);

        int idx = propertyIndex - 1;
        if (idx < 0 || idx >= pf.getNumProperties()) {
            throw new PrismException("Property index out of range: " + propertyIndex);
        }

        Property prop = pf.getPropertyObject(idx);

        prism.buildModelIfRequired();
        @SuppressWarnings("unchecked")
        CSGSimple<Double> trueGame = (CSGSimple<Double>) prism.getBuiltModelExplicit();

        boolean zeroSum = isZeroSumProperty(prop);
        boolean useRewards = isRewardProperty(prop);
        int horizon = derivePropertyHorizon(prop.getExpression(), pf);
        boolean finiteHorizon = horizon >= 0;
        MinMax minMax = deriveMinMax(prop);

        double rMax = deriveRMax(mf);
        double eps = (this.epsilon < 0) ? rMax / 10.0 : this.epsilon;

        return new PacRunSpec(
                trueGame,
                pf,
                prop,
                eps,
                confidence,
                rMax,
                horizon,
                finiteHorizon,
                solverString,
                zeroSum,
                useRewards,
                minMax,
                maxNumEpisodes
        );
    }

    // ===============================
    // Property analysis
    // ===============================

    private MinMax deriveMinMax(Property prop) throws PrismException {
        ExpressionStrategy strat = findStrategy(prop.getExpression());
        if (strat == null) {
            throw new PrismException("No strategy operator found in property");
        }

        Expression inner = stripParentheses(strat.getOperand(0));

        // ---------- ZERO-SUM ----------
        if (inner instanceof ExpressionProb prob) return minMaxFromRelOp(prob.getRelOp(), true);
        if (inner instanceof ExpressionReward rew) return minMaxFromRelOp(rew.getRelOp(), true);

        // ---------- GENERAL-SUM ----------
        if (inner instanceof ExpressionMultiNash multi) {
            RelOp relOp = multi.getRelOp();
            if (relOp == null) {
                throw new PrismException("MultiNash expression has no RelOp");
            }
            // For general-sum, direction applies to the aggregated objective
            return minMaxFromRelOp(relOp, false);
        }
        throw new PrismException("Unsupported property type for min/max derivation");
    }

    private MinMax minMaxFromRelOp(RelOp relOp, boolean zeroSum) throws PrismException {
        if (relOp == null) {
            throw new PrismException("RelOp is null");
        }
        if (relOp.isMax()) {
            return zeroSum ? MinMax.max() : MinMax.minMin(false, false).setMinUnc(true);
        }
        if (relOp.isMin()) {
            return zeroSum ? MinMax.min() : MinMax.minMin(true, true).setMinUnc(false);
        }
        // fallback for bounds (rare but safe)
        if (relOp.isLowerBound()) {
            return zeroSum ? MinMax.min() : MinMax.minMin(true, true).setMinUnc(false);
        }
        if (relOp.isUpperBound()) {
            return zeroSum ? MinMax.max() : MinMax.minMin(false, false).setMinUnc(true);
        }

        throw new PrismException("Unsupported RelOp: " + relOp);
    }

    private boolean isRewardProperty(Property prop) {
        ExpressionStrategy strat = findStrategy(prop.getExpression());
        if (strat == null) return false;

        Expression inner = stripParentheses(strat.getOperand(0));

        // ZERO-SUM case
        if (inner instanceof ExpressionReward) {
            return true;
        }
        // GENERAL-SUM case (multi-objective)
        if (inner instanceof ExpressionMultiNash multi) {
            for (ExpressionQuant q : multi.getOperands()) {
                if (q instanceof ExpressionMultiNashReward) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isZeroSumProperty(Property prop) {
        ExpressionStrategy strat = findStrategy(prop.getExpression());
        if (strat == null) return false;

        Expression inner = stripParentheses(strat.getOperand(0));

        if (inner instanceof ExpressionMultiNash multi) {
            return multi.getOperands().size() == 1;
        }
        return true;
    }

    private int derivePropertyHorizon(Expression expr, PropertiesFile pf) throws PrismException {
        ExpressionStrategy strat = findStrategy(expr);
        if (strat == null) return -1;

        Expression inner = stripParentheses(strat.getOperand(0));

        // ZERO-SUM
        if (inner instanceof ExpressionProb prob) {
            return extractTemporal(prob.getExpression(), pf);
        }

        if (inner instanceof ExpressionReward rew) {
            return extractTemporal(rew.getExpression(), pf);
        }

        // GENERAL-SUM
        if (inner instanceof ExpressionMultiNash multi) {
            int horizon = -1;

            for (ExpressionQuant q : multi.getOperands()) {
                int h = -1;

                if (q instanceof ExpressionMultiNashProb p) {
                    h = extractTemporal(p.getExpression(), pf);
                }
                else if (q instanceof ExpressionMultiNashReward r) {
                    h = extractTemporal(r.getExpression(), pf);
                }

                if (h < 0) return -1;
                horizon = Math.max(horizon, h);
            }

            return horizon;
        }

        return -1;
    }

    private int extractTemporal(Expression expr, PropertiesFile pf) throws PrismException {

        if (expr instanceof ExpressionTemporal t) {
            return deriveTemporalHorizon(t, pf);
        }

        Expression canonical = Expression.convertSimplePathFormulaToCanonicalForm(expr);
        if (canonical instanceof ExpressionTemporal t) {
            return deriveTemporalHorizon(t, pf);
        }

        return -1;
    }

    private int deriveTemporalHorizon(ExpressionTemporal t, PropertiesFile pf) throws PrismException {

        if (!t.hasBounds()) return -1;

        Expression ub = t.getUpperBound();
        if (ub == null) return -1;

        return ub.evaluateInt(pf.getConstantValues());
    }

    // ===============================
    // Reward bound extraction
    // ===============================

    private double deriveRMax(ModulesFile mf) {

        if (mf.getNumRewardStructs() == 0) return 1.0;

        Values consts = mf.getConstantValues();
        double rMax = 0.0;

        for (RewardStruct rs : mf.getRewardStructs()) {
            for (int i = 0; i < rs.getNumItems(); i++) {
                Expression expr = rs.getReward(i);

                try {
                    double val = expr.evaluateDouble(consts);
                    rMax = Math.max(rMax, Math.abs(val)); // alow negative rewards
                } catch (Exception e) {
                    return 1.0; // fallback if non-constant
                }
            }
        }
        return rMax > 0 ? rMax : 1.0;
    }

    // ===============================
    // AST utilities
    // ===============================

    private ExpressionStrategy findStrategy(Expression expr) {
        if (expr instanceof ExpressionStrategy s) return s;

        if (expr instanceof ExpressionUnaryOp u)
            return findStrategy(u.getOperand());

        if (expr instanceof ExpressionBinaryOp b) {
            ExpressionStrategy s = findStrategy(b.getOperand1());
            if (s != null) return s;
            return findStrategy(b.getOperand2());
        }

        return null;
    }

    private Expression stripParentheses(Expression expr) {
        while (expr instanceof ExpressionUnaryOp u &&
                Expression.isParenth(expr)) {
            expr = u.getOperand();
        }
        return expr;
    }

    // ===============================
    // Model selection
    // ===============================

    public Experiment setModel(CaseStudy model) {
        this.model = model;
        this.parameterValues = new Values();

        switch (model) {
//            case VERY_SIMPLE -> {
//                modelFile = "./prism-examples/csgs/learning/very_simple.prism";
//                propertiesFile = "./prism-examples/csgs/learning/very_simple.props";
//                propertyIndex = 1;
//                epsilon = 0.1;
//            }
            case SAFE_RISKY -> {
                modelFile = "./prism-examples/csgs/learning/safe_risky.prism";
                propertiesFile = "./prism-examples/csgs/learning/safe_risky.props";
                propertyIndex = 1;
            }
            case NO_NE -> { // TODO: problematic, need to be able to initialise MCs without solving
                modelFile = "./prism-examples/csgs/learning/no_ne.prism";
                propertiesFile = "./prism-examples/csgs/learning/no_ne.props";
                propertyIndex = 1;
                epsilon = 0.2;
            }
            case TRAFFIC_MERGE -> {
                modelFile = "./prism-examples/csgs/learning/traffic_merge.prism";
                propertiesFile = "./prism-examples/csgs/learning/traffic_merge.props";
                propertyIndex = 2;
//                epsilon = 0.2;
            }
            case TEST -> {
                modelFile = "./prism-examples/csgs/aloha/aloha_backoff2.prism";
                propertiesFile = "./prism-examples/csgs/aloha/aloha_backoff2.props";
                propertyIndex = 2;
                addParameters("D", 1, "bcmax", 1, "q", 0.05);
            }
        }
        return this;
    }
}