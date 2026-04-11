package learning.Estimators;

import explicit.*;
import parser.ast.PropertiesFile;
import prism.Prism;
import prism.PrismException;
import prism.Result;
import simulator.ModulesFileModelGenerator;
import learning.Simulation.StateActionPair;
import learning.Simulation.TransitionTriple;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Map;

public class PACLearner extends Estimator {

    public PACLearner(Prism prism, imdpcomp.Experiment ex) {
        super(prism, ex);
        this.name = "PAC-L1";
    }

    public L1MDP<Double> buildL1MDP(MDP<Double> mdp) throws PrismException {
        int numStates = mdp.getNumStates();
        L1MDPSimple<Double> l1 = new L1MDPSimple<>(numStates);

        for (int init : mdp.getInitialStates()) {
            l1.addInitialState(init);
        }
        l1.setStatesList(mdp.getStatesList());
        l1.setConstantValues(mdp.getConstantValues());
        l1.setVarList(mdp.getVarList());
        l1.setActions(mdp.getActions());

        for (Map.Entry<String, BitSet> e : mdp.getLabelToStatesMap().entrySet()) {
            l1.addLabel(e.getKey(), e.getValue());
        }

        for (int s = 0; s < numStates; s++) {
            int numChoices = mdp.getNumChoices(s);
            for (int i = 0; i < numChoices; i++) {
                String action = getActionString(mdp, s, i);
                StateActionPair sa = new StateActionPair(s, action);
                int n = sampleSizeMap.getOrDefault(sa, 0);

                Distribution<Double> distr = new Distribution<>(mdp.getEvaluator());
                ArrayList<Integer> succs = new ArrayList<>();

                final int finalS = s;
                mdp.forEachDoubleTransition(s, i, (int sFrom, int sTo, double p) -> {
                    succs.add(sTo);
                    double emp;
                    if (n > 0) {
                        emp = (double) samplesMap.getOrDefault(new TransitionTriple(finalS, action, sTo), 0) / (double) n;
                    } else {
                        emp = p;
                    }
                    if (emp > 0.0) {
                        distr.add(sTo, emp);
                    }
                });

                double radius = getL1WeissmannBound(succs.size(), n);
                l1.addActionLabelledChoice(s, distr, radius, action);
            }
        }

        l1.findDeadlocks(true);
        this.estimate = l1;
        return l1;
    }

    public double getL1WeissmannBound(int numSucc, int numSamples) {
        if (numSucc <= 1) return 0.0;
        if (numSamples <= 0) return 2.0;

        int m = Math.max(1, getNumLearnableTransitions());
        double alpha = (1.0 - ex.error_tolerance) / (double) m;
        double inside = Math.pow(2.0, numSucc) - 2.0;
        if (inside <= 0.0) return 0.0;

        double rad = Math.sqrt(2.0 * (Math.log(inside) - Math.log(alpha)) / (double) numSamples);
        if (Double.isNaN(rad) || Double.isInfinite(rad)) return 2.0;
        return Math.min(2.0, Math.max(0.0, rad));
    }

    public Result modelCheckL1Estimate(boolean robust, boolean verbose) throws PrismException {
        if (this.estimate == null) {
            throw new PrismException("L1 estimate has not been built yet");
        }

        UMDPModelChecker mc = new UMDPModelChecker(this.prism);
        mc.setGenStrat(true);
        mc.setPrecomp(true);
        mc.setErrorOnNonConverge(ex.errorOnNonConvergence);
        mc.setMaxIters(ex.maxVIIters);
        mc.setTermCritParam(1e-4);

        PropertiesFile pf = robust
                ? prism.parsePropertiesString(ex.robustSpec)
                : prism.parsePropertiesString(ex.optimisticSpec);

        ModulesFileModelGenerator<?> modelGen =
                ModulesFileModelGenerator.create(modulesFileMDP, this.prism);

        if (this.estimate.getConstantValues() != null) {
            modelGen.setSomeUndefinedConstants(this.estimate.getConstantValues());
        }

        mc.setModelCheckingInfo(modelGen, pf, modelGen);

        Result result = mc.check(this.estimate, pf.getProperty(0));

        if (verbose) {
            System.out.println("\nModel checking L1 estimate:");
            System.out.println((robust ? ex.robustSpec : ex.optimisticSpec) + " : " + result.getResultAndAccuracy());
        }

        return result;
    }

    @Override
    public double[] getCurrentResults() throws PrismException {
        long startTime;
        long buildTime;
        long robustTime;
        long optimisticTime;

        Result resultRobust;
        Result resultOptimistic;

        // ===== BUILD =====
        startTime = System.nanoTime();
        buildL1MDP(mdp);
        buildTime = System.nanoTime() - startTime;

        // ===== ROBUST =====
        startTime = System.nanoTime();
        resultRobust = modelCheckL1Estimate(true, true);
        robustTime = System.nanoTime() - startTime;

        // ===== OPTIMISTIC =====
        startTime = System.nanoTime();
        resultOptimistic = modelCheckL1Estimate(false, true);
        optimisticTime = System.nanoTime() - startTime;

        double robustVal = round((Double) resultRobust.getResult());
        double optimisticVal = round((Double) resultOptimistic.getResult());

        return new double[]{
                robustVal,
                optimisticVal,
                buildTime,
                robustTime,
                optimisticTime
        };
    }

    @Override
    public double[] getInitialResults() throws PrismException {
        return getCurrentResults();
    }
}