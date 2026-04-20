package learning;

import explicit.CSGSimple;
import explicit.rewards.CSGRewards;
import parser.Values;
import parser.ast.Coalition;
import parser.ast.ExpressionTemporal;
import parser.ast.ModulesFile;
import parser.ast.PropertiesFile;
import prism.Prism;
import prism.PrismException;
import prism.PrismLangException;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

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

                // model constants from the command line
                parameterValues = new Values();
                addParameters(
                        "D", 8,
                        "q", 0.9,
                        "eps", 1.0 / 257.0,
                        "bcmax", 1
                );

                // PAC-learning settings
                this.pacEps = 1.0 / 257.0;   // PAC accuracy, not the model constant "eps"
                this.pacDelta = 0.05;
                this.rMax = 1.0;
                this.horizon = 8;            // D = 8 for the bounded reachability part

                // for your current solver call
                this.eqType = 0;
                this.crit = 0;
                this.min = true;

                // property 7 is bounded probabilistic reachability nested inside a coalition query
                this.objectiveKind = PACLearner.ObjectiveKind.PROB_REACH_BOUNDED;
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

    public PacRunSpec buildPacRunSpec(Prism prism) throws PrismException, PrismLangException, FileNotFoundException {
        File mfFile = Prism.resolveFile(modelFile);
        File pfFile = Prism.resolveFile(propertiesFile);

        ModulesFile mf = prism.parseModelFile(mfFile);
        prism.loadPRISMModel(mf);
        prism.setPRISMModelConstants(parameterValues);

        PropertiesFile pf = prism.parsePropertiesFile(mf, pfFile);

        // Build the explicit CSG so PACLearner can use it directly
        prism.buildModelIfRequired();
        @SuppressWarnings("unchecked")
        CSGSimple<Double> trueGame = (CSGSimple<Double>) prism.getBuiltModelExplicit();

        List<Coalition> coalitions = buildCoalitions();
        List<ExpressionTemporal> exprs = new ArrayList<>();
        List<CSGRewards<Double>> rewards = new ArrayList<>();

        // For property 7 you are doing bounded reachability.
        // If your current PAC learner uses one bound per expr, keep a single entry.
        int[] bounds = new int[] { horizon };

        BitSet[] targets = buildTargets(trueGame);
        BitSet[] remain = buildRemain(trueGame);

        return new PacRunSpec(
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
                horizon
        );
    }

    private List<Coalition> buildCoalitions() {
        List<Coalition> coalitions = new ArrayList<>();
        coalitions.add(new Coalition(Arrays.asList("usr1")));
        coalitions.add(new Coalition(Arrays.asList("usr2", "usr3")));
        return coalitions;
    }

    private BitSet[] buildTargets(CSGSimple<Double> trueGame) {
        int n = trueGame.getNumStates();
        BitSet[] targets = new BitSet[2];
        targets[0] = new BitSet(n);
        targets[1] = new BitSet(n);

        // TODO: fill these by checking state valuations after building the model.
        // For property 7, these are the two bounded reachability target sets.
        //
        // targets[0] = { states satisfying s1=3 & t<=D }
        // targets[1] = { states satisfying s2=3 & s3=3 & t<=D }

        return targets;
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