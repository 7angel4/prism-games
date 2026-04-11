package learning.Estimators;

import common.Interval;
import explicit.MDP;
import explicit.MDPSimple;
import explicit.UMDP;
import explicit.UMDPModelChecker;
import imdpcomp.Experiment;
import learning.Simulation.StateActionPair;
import learning.Simulation.TransitionTriple;
import param.Function;
import parser.ast.Expression;
import parser.ast.ModulesFile;
import parser.ast.PropertiesFile;
import prism.*;
import simulator.ModulesFileModelGenerator;
import strat.MDStrategy;
import strat.MRStrategy;
import strat.Strategy;

import java.io.File;
import java.io.FileNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class Estimator {
    private final HashSet<TransitionTriple> transitionsOfInterest;
    protected String name = "Base";
    protected Prism prism;
    protected Experiment ex;
    protected HashMap<TransitionTriple, Integer> samplesMap;
    protected HashMap<StateActionPair, Integer> sampleSizeMap;
    protected HashMap<TransitionTriple, Interval<Double>> intervalsMap;
    protected Map<TransitionTriple, Double> constantMap;
    protected ModulesFile modulesFile;
    protected ModulesFile modulesFileIMDP;
    protected ModulesFile modulesFileMDP;
    protected MDP<Double> mdp;
    protected MDPSimple<Function> pmdp;
    protected String SULoptimum;
    protected double sulOpt;
    protected HashSet<Integer> prob01States;
    protected HashSet<Integer> rew0InfStates;
    protected UMDP<Double> estimate;
    protected UMDP<Double> bisimEstimate;
    protected UMDP<Double> marginalEstimate;
    protected UMDP<Double> convex_estimate;
    protected HashMap<TransitionTriple, Double> trueProbabilitiesMap;
    protected MDStrategy currentStrat;
    protected MDStrategy optimalStrat;
    private Map<Function, List<TransitionTriple>> functionMap;
    // Contains lists of similar transitions, whose counts can be tied
    private List<List<TransitionTriple>> similarTransitions;
    private int numLearnableTransitions = 0;
    private MRStrategy uniformStrat;

    // Bisimulation fields
    protected boolean useBisim = false;
    protected boolean tieAbstractExpressions = true;

    protected MDPSimple<Function> pmdpBisim = null;
    protected int[] bisimPartition = null;

    Estimator(Prism prism, Experiment ex) {
        this.prism = prism;
        this.ex = ex;

        this.samplesMap = new HashMap<>();
        this.sampleSizeMap = new HashMap<>();
        this.intervalsMap = new HashMap<>();

        this.prob01States = new HashSet<>();
        this.rew0InfStates = new HashSet<>();

        this.numLearnableTransitions = 0;
        this.transitionsOfInterest = new HashSet<>();
        this.trueProbabilitiesMap = new HashMap<>();

        this.constantMap = new HashMap<>();

        this.buildModulesFiles();
        this.tryBuildSUL();
        //this.processTransitions();
    }

    public void set_experiment(Experiment ex) {
        this.ex = ex;
        this.buildModulesFiles();
        this.tryBuildSUL();
        this.processTransitionsNaive();
    }

    private void buildModulesFiles() {
        try {
            this.modulesFile = this.prism.parseModelFile(new File(ex.certainModelFile));
            this.modulesFileIMDP = this.prism.parseModelFile(new File(ex.certainModelFile), ModelType.IMDP);
            this.modulesFileMDP = this.prism.parseModelFile(new File(ex.certainModelFile), ModelType.MDP);
        } catch (FileNotFoundException e) {
            System.out.println("Error file: " + e.getMessage());
            System.exit(1);
        } catch (PrismLangException e) {
            System.out.println("Error parsing file: " + e.getMessage());
            System.exit(1);
        } catch (NullPointerException e) {
            System.out.println("Error null: " + e.getMessage());
            System.exit(1);
        }
    }

    public Double round(double value) {
        return round(value, 8);
    }

    public Double round(double value, int precision) {
//        if (value > 9000.0) {
//            return Double.POSITIVE_INFINITY;
//        }
        if (value == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(precision, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    public void setSamplesMap(HashMap<TransitionTriple, Integer> samplesMap) {
        this.samplesMap = samplesMap;
    }

    public void setSampleSizeMap(HashMap<StateActionPair, Integer> sampleSizeMap) {
        this.sampleSizeMap = sampleSizeMap;
    }

    public void setObservationMaps(HashMap<TransitionTriple, Integer> samplesMap, HashMap<StateActionPair, Integer> sampleSizeMap) {
        setSampleSizeMap(sampleSizeMap);
        setSamplesMap(samplesMap);
    }

    public String getActionString(MDP mdp, int s, int i) {
        String action = (String) mdp.getAction(s, i);
        if (action == null) {
            action = "_empty";
        }
        return action;
    }

    public UMDP<Double> getEstimate() {
        return this.estimate;
    }

    private void tryBuildSUL() {
        try {
            this.buildSUL();
        } catch (PrismException e) {
            System.out.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Build the SUL from the prism model file
     */
    public void buildSUL() throws PrismException {
        this.prism.loadPRISMModel(modulesFile);
        if (ex.parameterValues != null) {
            this.prism.setPRISMModelConstants(ex.parameterValues);
        }

        this.prism.setGenStrat(true);
        this.prism.setStoreVector(true);
        this.prism.setMaxIters(ex.maxVIIters);

        Result result = this.prism.modelCheck(ex.spec);
        System.out.println("result : " + result);

        MDP<Double> mdp = (MDP<Double>) this.prism.getBuiltModelExplicit();

        this.SULoptimum = result.getResultAndAccuracy();
        this.sulOpt = (Double) result.getResult();
        this.optimalStrat = (MDStrategy) result.getStrategy();

        this.mdp = mdp;

        ArrayList<Integer> initialStates = (ArrayList<Integer>) this.mdp.getInitialStates();
        if (ex.type == Experiment.Type.REACH) {
            computeProb01States(result);
            for (int s : initialStates) {
                if (this.prob01States.contains(s)) {
                    System.out.println("Initial state " + s + " is a 01 prob state. Exiting.");
                    System.exit(0);
                }
            }
        } else if (ex.type == Experiment.Type.REWARD) {
            computeRew0InfStates(result);
        } else {
            System.out.println("ERROR: unsupported type unknown " + ex.type);
            System.exit(1);
        }
    }

    public MDP<Double> getSUL() {
        return this.mdp;
    }

    public double getSulOpt() {
        return this.sulOpt;
    }

    public double averageDistanceToSUL() {
        return -1.0;
    }

    public String getModelStats() {
        String stats = "%------\n%Model stats\n%";
        stats += "  #States: " + this.mdp.getNumStates() + "\n%";
        stats += "  #transitions: " + this.mdp.getNumTransitions() + ",  with  " + this.numLearnableTransitions + "  learnable components.\n%";
        stats += "  true MDP optimum for " + ex.robustSpec + "  =  " + this.SULoptimum + "\n%";
        stats += "------";
        return stats;

    }

    public HashSet<Integer> getTerminatingStates() {
        if (ex.type == Experiment.Type.REACH)
            return this.prob01States;
        else {
            return this.rew0InfStates;
        }
    }

    public void processTransitionsNaive() {
        this.numLearnableTransitions = 0;
        this.transitionsOfInterest.clear();
        int numStates = this.mdp.getNumStates();

        for (int s = 0; s < numStates; s++) {
            int numChoices = this.mdp.getNumChoices(s);
            final int state = s;
            for (int i = 0; i < numChoices; i++) {
                final String action = getActionString(this.mdp, s, i);
                this.mdp.forEachDoubleTransition(s, i, (int sFrom, int sTo, double p) -> {
                    if (0 < p && p < 1.0) {
                        this.numLearnableTransitions += 1;
                        this.transitionsOfInterest.add(new TransitionTriple(state, action, sTo));
                        this.trueProbabilitiesMap.put(new TransitionTriple(state, action, sTo), p);
                    }
                });
            }
        }

        switch (ex.tieParameters) {
            case NO_TYING -> {
            } // Number from above correct
            case FULL_TYING, DEPENDENCY_TYING -> {
                this.numLearnableTransitions = this.functionMap.size();
            }
        }
    }

    public double maxIntervalPointDistance(Interval<Double> interval, double p) {
        double lower = interval.getLower();
        double upper = interval.getUpper();
        double d1 = Math.abs(p - lower);
        double d2 = Math.abs(p - upper);
        double maxDist = Double.max(d1, d2);
        return maxDist;
    }


    public HashSet<TransitionTriple> getTransitionsOfInterest() {
        return this.transitionsOfInterest;
    }

    public int getNumLearnableTransitions() {
        return this.numLearnableTransitions;
    }

    public void computeProb01States(Result result) throws PrismException {
        //System.out.println("compute");
        StateVector vector = result.getVector();
        //System.out.println("vector = " + vector);
        int numStates = this.mdp.getNumStates();
        for (int s = 0; s < numStates; s++) {
            double value = (Double) vector.getValue(s);
            if (value == 0.0) {
                this.prob01States.add(s);
            } else if (value == 1.0) {
                this.prob01States.add(s);
            } else {
                continue;
            }
        }
    }

    public void computeRew0InfStates(Result result) throws PrismException {
        //System.out.println("compute");
        StateVector vector = result.getVector();
        //System.out.println("vector = " + vector);
        int numStates = this.mdp.getNumStates();
        for (int s = 0; s < numStates; s++) {
            double value = (Double) vector.getValue(s);
            if (value == 0.0) {
                this.rew0InfStates.add(s);
            } else if (value == Double.POSITIVE_INFINITY) {
                this.rew0InfStates.add(s);
            }
        }
    }

    public Strategy buildStrategy() throws PrismException {
        throw new UnsupportedOperationException("build strategy is undefined");
    }

    public Strategy buildUniformStrat() {
        if (this.uniformStrat == null) {
            MRStrategy strat = new MRStrategy(this.mdp);
            int numStates = this.mdp.getNumStates();
            for (int s = 0; s < numStates; s++) {
                int numChoices = this.mdp.getNumChoices(s);
                for (int i = 0; i < numChoices; i++) {
                    strat.setChoiceProbability(s, i, 1.0 / numChoices);
                }
            }
            this.uniformStrat = strat;
        }
        return this.uniformStrat;
    }


    public MDStrategy computeStrategyFromEstimate(UMDP<Double> estimate) throws PrismException {
        return this.computeStrategyFromEstimate(estimate, true);
    }

    public MDStrategy computeOptimisticStrategyFromEstimate(UMDP<Double> estimate) throws PrismException {
        return this.computeStrategyFromEstimate(estimate, false);
    }


    public Strategy buildWeightedOptimisticStrategy(UMDP<Double> estimate, double weight) throws PrismException {
        MDStrategy optStrat = this.currentStrat != null ? this.currentStrat : computeOptimisticStrategyFromEstimate(this.estimate);
        Strategy uniformStrat = buildUniformStrat();
        MRStrategy strat = new MRStrategy(this.mdp);
        int numStates = this.mdp.getNumStates();
        for (int s = 0; s < numStates; s++) {
            int numChoices = this.mdp.getNumChoices(s);
            int optimisticChoice = optStrat.getChoiceIndex(s);
            if (optimisticChoice >= 0 && numChoices > 1) {
                for (int i = 0; i < numChoices; i++) {
                    if (i == optimisticChoice)
                        strat.setChoiceProbability(s, i, weight);
                    else
                        strat.setChoiceProbability(s, i, (1.0 - weight) / (numChoices - 1));
                }
            } else {
                for (int i = 0; i < numChoices; i++) {
                    strat.setChoiceProbability(s, i, 1.0 / numChoices);
                }
            }
        }
        return strat;
    }

    public MDStrategy computeStrategyFromEstimate(UMDP<Double> estimate, boolean robust) throws PrismException {
        UMDPModelChecker mc = new UMDPModelChecker(this.prism);
        mc.setGenStrat(true);
        mc.setPrecomp(true);
        mc.setErrorOnNonConverge(ex.errorOnNonConvergence);
        mc.setMaxIters(ex.maxVIIters);

        PropertiesFile pf = robust
                ? prism.parsePropertiesString(ex.robustSpec)
                : prism.parsePropertiesString(ex.optimisticSpec);
        ModulesFileModelGenerator<?> modelGen = ModulesFileModelGenerator.create(modulesFileIMDP, this.prism);
        modelGen.setSomeUndefinedConstants(estimate.getConstantValues());
        mc.setModelCheckingInfo(modelGen, pf, modelGen);
        Expression exprTarget = pf.getProperty(0);
        Result result = mc.check(estimate, exprTarget);
        MDStrategy strat = (MDStrategy) result.getStrategy();
        //System.out.println("Strategy = " + strat);    // strat is null
        return strat;
    }

    public double[] getCurrentResults() throws PrismException {
        throw new UnsupportedOperationException("can't get results from estimator");
    }

    public double[] getInitialResults() throws PrismException {
        throw new UnsupportedOperationException("can't get results from estimator");
    }

    public String getName() {
        return this.name;
    }

    public Map<Function, List<TransitionTriple>> getFunctionMap() {
        return functionMap;
    }

    public void setFunctionMap(Map<Function, List<TransitionTriple>> functionMap) {
        this.functionMap = functionMap;
    }

    public List<List<TransitionTriple>> getSimilarTransitions() {
        return similarTransitions;
    }

    public void setSimilarTransitions(List<List<TransitionTriple>> similarTransitions) {
        this.similarTransitions = similarTransitions;
    }

    public MDPSimple<Function> getPmdp() {
        return pmdp;
    }

    public void setPmdp(MDPSimple<Function> pmdp) {
        this.pmdp = pmdp;
    }

    public int getNumLearnableComponents() {
        return -1;
    }

    public void setUseBisimAggregation(boolean flag) { this.useBisim = flag; }

    public void setTieAbstractExpressions(boolean flag) { this.tieAbstractExpressions = flag; }

    /** Provide the bisimulation topology to aggregate counts/labelling. */
    public void setBisimulationTopology(MDPSimple<param.Function> abstractMDP, int[] partition) {
        this.pmdpBisim = abstractMDP;
        this.bisimPartition = partition;
    }

}
