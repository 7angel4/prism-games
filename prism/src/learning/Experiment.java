package learning;

import explicit.CSGSimple;
import explicit.StateModelChecker;
import explicit.StateValues;
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

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

@SuppressWarnings({"unchecked", "rawtypes"})
public class Experiment
{
    public enum Model {
        TEST_CSG
    }

    public static final class PacRunSpec {
        public final Prism prism;
        public final PropertiesFile propertiesFile;
        public final Property property;

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

        public final String solverString;

        public PacRunSpec(
                Prism prism,
                PropertiesFile propertiesFile,
                Property property,
                CSGSimple<Double> trueGame,
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
                int horizon,
                String solverString
        ) {
            this.prism = prism;
            this.propertiesFile = propertiesFile;
            this.property = property;
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
            this.solverString = solverString;
        }
    }

    public Model model;
    public String modelFile;
    public String propertiesFile;
    public int propertyIndex; // 1-based

    public Values parameterValues = new Values();
    public String solverString = "";

    public double pacEps = 1.0 / 257.0;
    public double pacDelta = 0.05;
    public double rMax = 1.0;
    public int horizon = 8;

    public int eqType = 0;
    public int crit = 0;
    public boolean min = false;

    public PACLearner.ObjectiveKind objectiveKind = PACLearner.ObjectiveKind.PROB_REACH;

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

        prism.buildModelIfRequired();
        @SuppressWarnings("unchecked")
        CSGSimple<Double> trueGame = (CSGSimple<Double>) prism.getBuiltModelExplicit();

        ExpressionStrategy stratExpr = findFirstStrategyExpression(prop.getExpression());
        if (stratExpr == null) {
            throw new PrismException("Could not find an ExpressionStrategy inside property " + propertyIndex);
        }

        List<Coalition> coalitions =
                stratExpr.getCoalitions() == null
                        ? Collections.emptyList()
                        : new ArrayList<>(stratExpr.getCoalitions());

        Expression inner = stripParentheses(stratExpr.getOperand(0));
        if (!(inner instanceof ExpressionMultiNash multiNash)) {
            throw new PrismException("Expected ExpressionMultiNash inside strategy expression, got " + inner.getClass().getSimpleName());
        }

        boolean min = multiNash.getRelOp() != null && multiNash.getRelOp().isMin();

        List<ExpressionQuant> formulae = multiNash.getOperands();

        BitSet[] targets = new BitSet[formulae.size()];
        BitSet[] remain = new BitSet[formulae.size()];
        int[] bounds = new int[formulae.size()];
        Arrays.fill(bounds, -1);

        List<ExpressionTemporal> exprs = new ArrayList<>();
        List<CSGRewards<Double>> rewards = new ArrayList<>();

        boolean hasRewards = false;
        boolean hasBounded = false;

        for (int p = 0; p < formulae.size(); p++) {
            ExpressionQuant q = formulae.get(p);

            if (q instanceof ExpressionMultiNashProb probQ) {
                Expression path = Expression.convertSimplePathFormulaToCanonicalForm(probQ.getExpression());
                if (!(path instanceof ExpressionTemporal temporal)) {
                    throw new PrismException("Expected a temporal formula, got " + path.getClass().getSimpleName());
                }

                exprs.add(temporal);

                switch (temporal.getOperator()) {
                    case ExpressionTemporal.P_F -> {
                        targets[p] = evaluateStateFormulaToBitSet(prism, trueGame, pf, temporal.getOperand2());
                        remain[p] = fullStateSet(trueGame.getNumStates());
                    }
                    case ExpressionTemporal.P_U -> {
                        targets[p] = evaluateStateFormulaToBitSet(prism, trueGame, pf, temporal.getOperand2());
                        Expression guard = temporal.getOperand1();
                        remain[p] = Expression.isTrue(guard)
                                ? fullStateSet(trueGame.getNumStates())
                                : evaluateStateFormulaToBitSet(prism, trueGame, pf, guard);

                        if (temporal.hasBounds()) {
                            IntegerBound b = IntegerBound.fromExpressionTemporal(temporal, pf.getConstantValues(), true);
                            if (!b.hasUpperBound()) {
                                throw new PrismException("Only an upper bounded until is supported.");
                            }
                            bounds[p] = b.getHighestInteger();
                            hasBounded = true;
                        }
                    }
                    default -> throw new PrismException("Unsupported temporal operator: " + temporal.getOperatorSymbol());
                }
            } else if (q instanceof ExpressionMultiNashReward rewQ) {
                hasRewards = true;
                Expression path = Expression.convertSimplePathFormulaToCanonicalForm(rewQ.getExpression());
                if (!(path instanceof ExpressionTemporal temporal)) {
                    throw new PrismException("Expected a temporal reward formula, got " + path.getClass().getSimpleName());
                }
                exprs.add(temporal);

                throw new PrismException("Reward-based multi-objective properties are not wired into this harness yet.");
            } else {
                throw new PrismException("Unsupported multi-objective term: " + q.getClass().getSimpleName());
            }
        }

        if (hasRewards) {
            objectiveKind = hasBounded ? PACLearner.ObjectiveKind.REW_BOUNDED : PACLearner.ObjectiveKind.REW_REACH;
        } else {
            objectiveKind = hasBounded ? PACLearner.ObjectiveKind.PROB_REACH_BOUNDED : PACLearner.ObjectiveKind.PROB_REACH;
        }

        if (remain == null) {
            remain = buildRemain(trueGame);
        }

        return new PacRunSpec(
                prism,
                pf,
                prop,
                trueGame,
                objectiveKind,
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
                horizon,
                solverString
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

        explicit.StateModelChecker mc =
                explicit.StateModelChecker.createModelChecker(trueGame.getModelType(), prism);
        mc.setModelCheckingInfo(prism.getModelInfo(), pf, prism.getRewardGenerator());

        StateValues sv = mc.checkExpression(trueGame, stateFormula, null);
        return (BitSet) sv.getBitSet().clone();
    }

    private BitSet fullStateSet(int n) {
        BitSet bs = new BitSet(n);
        bs.set(0, n);
        return bs;
    }

    private BitSet[] buildRemain(CSGSimple<Double> trueGame) {
        int n = trueGame.getNumStates();
        BitSet[] remain = new BitSet[2];
        remain[0] = fullStateSet(n);
        remain[1] = fullStateSet(n);
        return remain;
    }
}