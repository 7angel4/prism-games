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
        ALOHA,
        VERY_SIMPLE,
        SIMPLE,
        SAFE_RISKY
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
                MinMax minMax
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
                minMax
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
        if (inner instanceof ExpressionProb prob) return minMaxFromRelOp(prob.getRelOp());
        if (inner instanceof ExpressionReward rew) return minMaxFromRelOp(rew.getRelOp());
        return null; // should not use minMax for general-sum properties
    }

    private MinMax minMaxFromRelOp(RelOp relOp) throws PrismException {
        if (relOp == null) {
            throw new PrismException("RelOp is null");
        }

        if (relOp.isMax()) {
            return MinMax.max();
        }

        if (relOp.isMin()) {
            return MinMax.min();
        }

        // fallback for bounds (rare but safe)
        if (relOp.isLowerBound()) {
            return MinMax.min();
        }

        if (relOp.isUpperBound()) {
            return MinMax.max();
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
                    rMax = Math.max(rMax, Math.abs(val));
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
            case ALOHA -> {
                modelFile = "./prism-examples/csgs/learning/aloha.prism";
                propertiesFile = "./prism-examples/csgs/learning/aloha.props";
                propertyIndex = 1;
            }
            case VERY_SIMPLE -> {
                modelFile = "./prism-examples/csgs/learning/very_simple.prism";
                propertiesFile = "./prism-examples/csgs/learning/very_simple.props";
                propertyIndex = 1;
                epsilon = 0.1;
            }
            case SIMPLE -> {
                modelFile = "./prism-examples/csgs/learning/simple.prism";
                propertiesFile = "./prism-examples/csgs/learning/simple.props";
                propertyIndex = 2;
                epsilon = 0.5;
            }
            case SAFE_RISKY -> {
                modelFile = "./prism-examples/csgs/learning/safe_risky.prism";
                propertiesFile = "./prism-examples/csgs/learning/safe_risky.props";
                propertyIndex = 2;
                epsilon = 0.5;
            }
        }
        return this;
    }
}