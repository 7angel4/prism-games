package imdpcomp;

import explicit.ConstructModel;
import parser.Values;
import parser.ast.Expression;

import java.util.ArrayList;

import static explicit.ConstructModel.*;
import static explicit.ConstructModel.CompositionType.*;
import static imdpcomp.Experiment.IntervalAbstractionMode.*;
import static imdpcomp.Experiment.ParameterTying.*;

public class Experiment {
    public Model model;
    public Type type;
    public String modelFile;
    public String certainModelFile; //TODO: replace this with UMDP
    public String dtmcSpec;
    public String invspec;
    public String spec;
    public String robustSpec;
    public String optimisticSpec;
    public Expression dtmcSpec_bisim;
    public Expression spec_bism;
    public Expression robustSpec_bisim;
    public Expression optimisticSpec_bisim;
    public Values parameterValues = new Values();
    public Values identParameters = new Values();
    public Values exactValues = new Values();
    public ParameterTying tieParameters = FULL_TYING;
    public boolean factored = false;
    public CompositionType compositionType = INTERVAL_PRODUCT;
    public double error_tolerance = 0.999;
    public double strategyWeight = 0.9;
    public int seed = 5;
    public int iterations = 100_000;
    public int max_episode_length = 50;
    public int multiplier = 5;
    public int maxVIIters = 20000;
    public boolean errorOnNonConvergence = true;
    public ArrayList<Integer> resultIterations = new ArrayList<>();
    public boolean useParametricConvex = true;
    public boolean doBisim = false;
    public int obbtMaxIters = 0;
    public double obbtEps = 10e-6;
    public boolean useDTMCLP = true;
    public boolean useLPToIntervals = false;
    public IntervalAbstractionMode intervalAbstractionMode = FAST;
    public int exprBoundWorkers = 8;
    public double apsLambda = 1e-2;
    public double apsR = 1;
    public double apsS = 1;
    public double apsDelta = 1 - error_tolerance;
    public boolean useApsEllipsoid = false;
    public boolean forceEllipsoidSOCP = true;
    public boolean useVertexPrecomp = true;
    public boolean verboseBisim = false;


    public enum ParameterTying {
        NO_TYING,
        FULL_TYING,
        DEPENDENCY_TYING // Only relevant for factored
    }

    public enum IntervalAbstractionMode {
        EXACT,
        FAST
    }

    public Experiment(Model model){
        this.setModel(model);
    }

    public Experiment(Model model, CompositionType type){
        this.setModel(model);
        this.compositionType = type;
    }

    public Experiment setIntervalAbstractionMode(IntervalAbstractionMode mode){
        this.intervalAbstractionMode = mode;
        return this;
    }

    public enum Model {
        AIRCRAFT,
        AIRCRAFT_MULTI_SLIP,
        LAKE_SWARM,
        LAKE_SWARM_MULTI_SLIP,
        COIN,
        CSMA,
        RABIN,
        CHAIN,
        CHAIN_MULTI,
        CHAIN_MULTI_SINGLE,
        CHAIN_CONVEX,
        DICE_2,
        DICE_3,
        HERMAN_3,
        DRONE,
        DRONE_MULTI,
        DRONE_MULTI_2,
        STOCK_TRADING_2_2,
        STOCK_TRADING_3_2,
        STOCK_TRADING_2_3,
        STOCK_TRADING_3_3,
        SYSADMIN,
        BETTING_GAME_CONVEX,
        BETTING_GAME_CONVEX_ADAPTIVE,
        SYSADMIN_CONVEX,
        GRID_MIXTURE_1,
        GRID_MIXTURE_STORM,
        GRID_MIXTURE_LAVA,
        ENGAGEMENT,
        ENGAGEMENT_ADAPTIVE,
        ENGAGEMENT_ADAPTIVE_5,
        ENGAGEMENT_ADAPTIVE_10,
        ENGAGEMENT_ADAPTIVE_100,
        ENGAGEMENT_ADAPTIVE_1000,
        KEY_DOOR_MAZE,
        ROUTER,
        DRONE_MIXTURE,
        DRONE_MIXTURE_STEPS,
        SAV2,
        SAV2_ADAPTIVE,
        SAV2_ADAPTIVE_5,
        SAV2_ADAPTIVE_100,
        AIRCRAFT_MIXTURE_ONEMOD,
        AIRCRAFT_MIXTURE_ONEMOD_ADAPTIVE,
        AIRCRAFT_MIXTURE_POSITION,
        EPIDEMIC,
        SIMPLE_BISIM,
        SIMPLE_BISIM_MDP,
        ROUTING_BISIM,
        PNUELI_ZUCK,
        TEST_BISIM,
        CROWDS,
        CROWDS_PARAM,
        BRP,
        EGL,
        NAND,
        GLIDER,
        BETTING_GAME_PARALLEL,
        TEST_DTMC
    }

    public enum Type {
        REACH,
        REWARD
    }

    public Experiment setCompositonType(CompositionType type){
        this.compositionType = type;
        return this;
    }

    public Experiment useAPSEllipsoid(boolean use) {
        this.useApsEllipsoid = use;
        return this;
    }

    public Experiment setValues(Values values) {
        this.parameterValues = values;
        return this;
    }

    public Experiment setSingleValue(String name, Object value) {
        this.parameterValues.setValue(name, value);
        return this;
    }

    public Experiment setTieParameters(ParameterTying tieParameters) {
        this.tieParameters = tieParameters;
        return this;
    }

    public Experiment setExactValues(Values values) {
        this.exactValues = values;
        return this;
    }

    public Experiment setParametricConvex(boolean useParametricConvex) {
        this.useParametricConvex = useParametricConvex;
        return this;
    }

    public Experiment useBisimulation(boolean useBisimulation) {
        this.doBisim = useBisimulation;
        return this;
    }

    public Experiment useOBBT(int obbtMaxIters){
        this.obbtMaxIters = obbtMaxIters;
        return this;
    }

    public Experiment useLPToIMDP(boolean useLPToIMDP) {
        this.useLPToIntervals = useLPToIMDP;
        return this;
    }

    public Experiment useVertexPrecomp(boolean useVertexPrecomp) {
        this.useVertexPrecomp = useVertexPrecomp;
        return this;
    }

    public Experiment setVerboseBisim(boolean verboseBisim) {
        this.verboseBisim = verboseBisim;
        return this;
    }

    public boolean resultIteration(int i) {
        return this.resultIterations.contains(i);
    }

    private void setModelFiles(String modelFile, String certainModelFile) {
        this.modelFile = modelFile;
        this.certainModelFile = certainModelFile;
    }

    private void setModelFiles(String sharedModelFile) {
        setModelFiles(sharedModelFile, sharedModelFile);
    }

    private void setSpecs(Type type, String robustSpec, String optimisticSpec, String dtmcSpec, String spec) {
        this.type = type;
        this.robustSpec = robustSpec;
        this.optimisticSpec = optimisticSpec;
        this.dtmcSpec = dtmcSpec;
        this.spec = spec;
    }

    private void setSpecsWithInverse(Type type, String robustSpec, String optimisticSpec, String dtmcSpec, String spec, String invSpec) {
        setSpecs(type, robustSpec, optimisticSpec, dtmcSpec, spec);
        this.invspec = invSpec;
    }

    private void setLearningSettings(int multiplier, int maxEpisodeLength, int maxVIIters) {
        this.multiplier = multiplier;
        this.max_episode_length = maxEpisodeLength;
        this.maxVIIters = maxVIIters;
    }

    private void addParameters(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Parameter name/value pairs must be even.");
        }

        for (int i = 0; i < nameValuePairs.length; i += 2) {
            Object name = nameValuePairs[i];
            if (!(name instanceof String parameterName)) {
                throw new IllegalArgumentException("Parameter name must be a String.");
            }
            this.parameterValues.addValue(parameterName, nameValuePairs[i + 1]);
        }
    }

    public Experiment setModel(Model model) {
        this.model = model;
        switch (model) {
            case AIRCRAFT -> {
                setModelFiles("../models/aircraft_collision/aircraft_10x20_resolution_3.prism", "../models/aircraft_collision/aircraft_10x20_resolution_3_certain.prism");
                setSpecs(Type.REACH, "Pmaxmin=? [!\"collision\" U \"goal\"]", "Pmaxmax=? [!\"collision\" U \"goal\"]", "P=? [!\"collision\" U \"goal\"]", "Pmax=? [!\"collision\" U \"goal\"]");
                this.max_episode_length = 11;

                // Set Parameter Values
                addParameters(
                        "eps", 0.02,
                        "r", 0.80,
                        "p", 0.2,
                        "maxX", 15,
                        "maxY", 15,
                        "d2", 0.1,
                        "d3", 0.05,
                        "drift1", 0.05,
                        "drift2", 0.05,
                        "drift3", 0.05
                );
            }

            case AIRCRAFT_MULTI_SLIP -> {
                setModelFiles("../models/aircraft_collision_multislip/aircraft_10x20_resolution_3.prism", "../models/aircraft_collision_multislip/aircraft_10x20_resolution_3_certain.prism");
                setSpecs(Type.REACH, "Pmaxmin=? [!\"collision\" U \"goal\"]", "Pmaxmax=? [!\"collision\" U \"goal\"]", "P=? [!\"collision\" U \"goal\"]", "Pmax=? [!\"collision\" U \"goal\"]");

                // Set Parameter Values
                addParameters(
                        "eps", 0.02,
                        "r", 0.80,
                        "p", 0.2,
                        "maxX", 15,
                        "maxY", 10
                );
            }

            case LAKE_SWARM -> {
                setModelFiles("../models/lake/frozen_lake_swarm_eps.prism", "../models/lake/frozen_lake_swarm_eps_certain.prism");
                setSpecs(Type.REWARD, "Rminmax=? [ F goal ]", "Rminmin=? [ F goal ]", "R=? [ F goal ]", "Rmin=? [ F goal ]");

                this.max_episode_length = 50;

                // Set Parameter Values
                addParameters(
                        "eps", 0.05,
                        "N", 10,
                        "M", 6,
                        "p", 0.3
                );
            }

            case LAKE_SWARM_MULTI_SLIP -> {
                setModelFiles("../models/lake_multislip_large9/frozen_lake_swarm_eps.prism", "../models/lake_multislip_large9/frozen_lake_swarm_eps_certain.prism");
                setSpecs(Type.REWARD, "Rminmax=? [ F goal ]", "Rminmin=? [ F goal ]", "R=? [ F goal ]", "Rmin=? [ F goal ]");

                this.max_episode_length = 100;

                // Set Parameter Values
                addParameters(
                        "eps", 0.04,
                        "N", 10,
                        "M", 6,
                        "p", 0.45
                );

            }

            case STOCK_TRADING_2_2 -> {
                setModelFiles("../models/stockmarket/stock_trading_2_2.pm");
                setSpecs(Type.REWARD, "Rmaxmin=? [ F goal ]", "Rmaxmax=? [ F goal ]", "R=? [ F goal ]", "Rmax=? [ F goal ]");

                this.max_episode_length = 11;

                // Set Parameter Values
                addParameters(
                        "T", 10,
                        "BASE", 0.1,
                        "SCALE", 0.8
                );
            }

            case STOCK_TRADING_3_2 -> {
                setModelFiles("../models/stockmarket/stock_trading_3_2.pm");
                setSpecs(Type.REWARD, "Rmaxmin=? [ F goal ]", "Rmaxmax=? [ F goal ]", "R=? [ F goal ]", "Rmax=? [ F goal ]");

                this.max_episode_length = 11;

                // Set Parameter Values
                addParameters(
                        "T", 10,
                        "BASE", 0.1,
                        "SCALE", 0.8
                );
            }

            case STOCK_TRADING_2_3 -> {
                setModelFiles("../models/stockmarket/stock_trading_2_3.pm");
                setSpecs(Type.REWARD, "Rmaxmin=? [ F goal ]", "Rmaxmax=? [ F goal ]", "R=? [ F goal ]", "Rmax=? [ F goal ]");

                this.max_episode_length = 11;

                // Set Parameter Values
                addParameters(
                        "T", 10,
                        "BASE", 0.1,
                        "SCALE", 0.8
                );
            }

            case STOCK_TRADING_3_3 -> {
                setModelFiles("../models/stockmarket/stock_trading_3_3.pm");
                setSpecs(Type.REWARD, "Rmaxmin=? [ F goal ]", "Rmaxmax=? [ F goal ]", "R=? [ F goal ]", "Rmax=? [ F goal ]");

                this.max_episode_length = 11;

                // Set Parameter Values
                addParameters(
                        "T", 5,
                        "BASE", 0.1,
                        "SCALE", 0.8
                );
            }

            case SYSADMIN -> {
                int N = 5;
                int T = 20;

                setModelFiles(String.format("../models/sysadmin/sysadmin_ring_N%s_T%s.pm", N, T));
                setSpecs(Type.REWARD, "Rmaxmin=? [ F (t_0 = T) ]", "Rmaxmax=? [ F (t_0 = T) ]", "R=? [ F (t_0 = T) ]", "Rmax=? [ F (t_0 = T) ]");

                this.max_episode_length = 6;

                // Set Parameter Values
                addParameters(
                        "T", T,
                        "N", N,
                        "p0", 0.1,
                        "p1", 0.6
                );
            }

            case SYSADMIN_CONVEX -> {
                int N = 10;

                setModelFiles("../parametric_convex_models/sys_admin.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [ !\"fail\" U \"goal\" ]", "Pmaxmax=? [ !\"fail\" U \"goal\" ]", "P=? [ !\"fail\" U \"goal\" ]", "Pmax=? [ !\"fail\" U \"goal\" ]", "Pmin=? [ !\"fail\" U \"goal\" ]");

                this.max_episode_length = N;
                this.maxVIIters = 10000000;

                // Set Parameter Values
                addParameters(
                        "N", N,
                        "p", 1.0 / (2 * N),
                        "q", 1.0 / (2.01 * N)
                );
            }


            case COIN -> {
                setModelFiles("../models/coin/coin4.prism", "../models/coin/coin4_certain.prism");
                this.robustSpec = "R{\"flips\"}maxmin=? [ F \"finished\" ]";
                this.dtmcSpec = "R{\"flips\"}=? [ F \"finished\" ]";

                // Set Parameter Values
                addParameters(
                        "eps", 0.05,
                        "p", 0.45,
                        "K", 2
                );
            }

            case CSMA -> {
                setModelFiles("../models/csma/csma3_2.prism", "../models/csma/csma3_2_certain.prism");
                this.robustSpec = "R{\"time\"}minmax=? [ F \"all_delivered\" ]";
                this.dtmcSpec = "R{\"time\"}=? [ F \"all_delivered\" ]";

                // Set Parameter Values
                addParameters(
                        "p1", 0.5,
                        "p2", 0.25,
                        "eps", 0.1
                );
            }

            case RABIN -> {
                setModelFiles("../models/rabin/rabin_rewards_3.prism", "../models/rabin/rabin_rewards_3_certain.prism");
                this.robustSpec = "Pmaxmin=?[ !\"one_critical\" U (p1=2) {draw1=1 & !\"one_critical\"}{min} ]";
                this.dtmcSpec = "P=?[ !\"one_critical\" U (p1=2) {draw1=1 & !\"one_critical\"}{min} ]";

                // Set Parameter Values
                addParameters("eps", 0.02); // 0 <= eps <= 0.03125
            }

            case CHAIN -> {
                setModelFiles("../models/chain/chain_2.prism", "../models/chain/chain_2_certain.prism");
                setSpecs(Type.REWARD, "Rminmax=? [F \"goal\"]", "Rminmin=? [F \"goal\"]", "R=? [F \"goal\"]", "Rmin=? [F \"goal\"]");

                // Set Parameter Values
                addParameters(
                        "H", 5,
                        "p", 0.4,
                        "q", 0.6,
                        "r", 0.5,
                        "eps", 0.02
                );
            }

            case CHAIN_MULTI -> {
                setModelFiles("../models/chain/chain_multi_succ.prism", "../models/chain/chain_multi_succ_certain.prism");
                setSpecs(Type.REWARD, "Rminmax=? [F \"goal\"]", "Rminmin=? [F \"goal\"]", "R=? [F \"goal\"]", "Rmin=? [F \"goal\"]");

                // Set Parameter Values
                addParameters(
                        "H", 8,
                        "p", 0.4,
                        "q", 0.6,
                        "r", 0.5,
                        "eps", 0.02
                );

                setLearningSettings(2, 20, 100000);
            }

            case CHAIN_MULTI_SINGLE -> {
                setModelFiles("../models/chain/chain_multi_succ_single.prism", "../models/chain/chain_multi_succ_single_certain.prism");
                setSpecs(Type.REWARD, "Rminmax=? [F \"goal\"]", "Rminmin=? [F \"goal\"]", "R=? [F \"goal\"]", "Rmin=? [F \"goal\"]");

                // Set Parameter Values
                addParameters(
                        "H", 9,
                        "p", 0.6,
                        "q", 0.4,
                        "r", 0.5,
                        "eps", 0.02
                );
            }

            case CHAIN_CONVEX -> {
                setModelFiles("../parametric_convex_models/chain_convex.prism");
                setSpecsWithInverse(Type.REWARD, "Rminmax=? [F \"goal\"]", "Rminmin=? [F \"goal\"]", "R=? [F \"goal\"]", "Rmin=? [F \"goal\"]", "Rmax=? [F \"goal\"]");
                setLearningSettings(2, 20, 20000);

                // Set Parameter Values
                addParameters(
                        "p", 0.1,
                        "q", 0.12
                );
            }

            case BETTING_GAME_CONVEX -> {
                setModelFiles("../parametric_convex_models/bet_fav.prism");
                setSpecsWithInverse(Type.REWARD, "Rmaxmin=? [F \"done\"]", "Rmaxmax=? [F \"done\"]", "R=? [F \"done\"]", "Rmax=? [F \"done\"]", "Rmin=? [F \"done\"]");
                setLearningSettings(4, 10, 20000);

                // Set Parameter Values
                addParameters(
                        "n", 10,
                        "p", 0.55
                );
            }

            case BETTING_GAME_CONVEX_ADAPTIVE -> {
                setModelFiles("../parametric_convex_models/bet_fav_adaptive.prism");
                setSpecsWithInverse(Type.REWARD, "Rmaxmin=? [F \"done\"]", "Rmaxmax=? [F \"done\"]", "R=? [F \"done\"]", "Rmax=? [F \"done\"]", "Rmin=? [F \"done\"]");
                setLearningSettings(2, 25, 200000);

                // Set Parameter Values
                addParameters(
                        "n", 150,
                        "p", 0.55
                );
            }

            case GRID_MIXTURE_1 -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/grid_mixture.prism");
                setSpecs(Type.REWARD, "R{\"steps\"}minmax=? [ F \"goal\" ]", "R{\"steps\"}minmin=? [ F \"goal\" ]", "R{\"steps\"}=? [ F \"goal\" ]", "R{\"steps\"}min=? [ F \"goal\" ]");
                setLearningSettings(2, 30, 20000);

                // Set Parameter Values
                addParameters(
                        "theta1", 0.3,
                        "theta2", 0.4
                );
            }

            case GRID_MIXTURE_STORM -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/grid_mixture_storm.prism");
                setSpecs(Type.REWARD, "R{\"total_cost\"}minmax=? [ F \"goal\" ]", "R{\"total_cost\"}minmin=? [ F \"goal\" ]", "R{\"total_cost\"}=? [ F \"goal\" ]", "R{\"total_cost\"}min=? [ F \"goal\" ]");
                setLearningSettings(2, 20, 20000);

                // Set Parameter Values
                addParameters(
                        "theta1", 0.3,
                        "theta2", 0.4
                );
            }

            case GRID_MIXTURE_LAVA -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/grid_mixture_lava.prism");
                setSpecs(Type.REWARD, "R{\"total_cost\"}minmax=? [ F \"goal\" ]", "R{\"total_cost\"}minmin=? [ F \"goal\" ]", "R{\"total_cost\"}=? [ F \"goal\" ]", "R{\"total_cost\"}min=? [ F \"goal\" ]");
                setLearningSettings(2, 25, 20000);

                // Set Parameter Values
                addParameters(
                        "theta1", 0.3,
                        "theta2", 0.4
                );
            }

            case ENGAGEMENT -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/engagement.prism");
                setSpecs(Type.REWARD, "Rminmax = ? [ F (\"purchase\" | \"churn\") ]", "Rminmin = ? [ F (\"purchase\" | \"churn\") ]", "R = ? [ F (\"purchase\" | \"churn\") ]", "Rmin = ? [ F (\"purchase\" | \"churn\") ]");
                setLearningSettings(2, 50, 100000);

                // Set Parameter Values
                addParameters(
                        "theta1", 0.3,
                        "theta2", 0.4
                );
            }

            case SIMPLE_BISIM -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/simple_bisim.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F \"cache\"]", "Pmaxmax = ? [ F \"cache\"]", "P = ? [ F \"cache\"]", "P min= ? [ F \"cache\"]");
                setLearningSettings(2, 50, 20000);

                // Set Parameter Values
                addParameters(
                        "p", 0.2,
                        "q", 0.3,
                        "r", 0.4
                );
            }

            case SIMPLE_BISIM_MDP -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/simple_mdp_bisim.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F \"drop\" ]", "Pmaxmax = ? [ F \"drop\"]", "P = ? [ F \"drop\"]", "P max= ? [ F \"drop\"]");
                setLearningSettings(2, 50, 20000);

                // Set Parameter Values
                addParameters(
                        "p", 0.35,
                        "q", 0.65,
                        "r", 0.4
                );
            }

            case ROUTING_BISIM -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/routing_bisim.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ !\"fail\" U \"goal\" ]", "Pmaxmax = ? [ !\"fail\" U \"goal\" ]", "P = ? [ !\"fail\" U \"goal\" ]", "Pmax = ? [ !\"fail\" U \"goal\" ]");
                setLearningSettings(2, 50, 20000);

// sizes
                this.parameterValues.addValue("L", 15);
                this.parameterValues.addValue("M", 500);
                this.parameterValues.addValue("B", 6);

// dynamics
                this.parameterValues.addValue("h",  0.01);
                this.parameterValues.addValue("s",  0.15);

// upper-layer forward (same as your original spirit)
                this.parameterValues.addValue("pL", 0.20);
                this.parameterValues.addValue("pM", 0.12);
                this.parameterValues.addValue("pN", 0.08);

// bottom-layer bucket vectors (sumRL=0.18, sumRM=0.12, sumRN=0.08)
                this.parameterValues.addValue("rL1", 0.060);
                this.parameterValues.addValue("rL2", 0.040);
                this.parameterValues.addValue("rL3", 0.030);
                this.parameterValues.addValue("rL4", 0.025);
                this.parameterValues.addValue("rL5", 0.015);
                this.parameterValues.addValue("rL6", 0.010);

                this.parameterValues.addValue("rM1", 0.045);
                this.parameterValues.addValue("rM2", 0.030);
                this.parameterValues.addValue("rM3", 0.020);
                this.parameterValues.addValue("rM4", 0.015);
                this.parameterValues.addValue("rM5", 0.005);
                this.parameterValues.addValue("rM6", 0.005);

                this.parameterValues.addValue("rN1", 0.030);
                this.parameterValues.addValue("rN2", 0.020);
                this.parameterValues.addValue("rN3", 0.015);
                this.parameterValues.addValue("rN4", 0.010);
                this.parameterValues.addValue("rN5", 0.003);
                this.parameterValues.addValue("rN6", 0.002);
            }

            case TEST_BISIM -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/test_bisim.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F (\"goal\") ]", "Pmaxmax = ? [ F (\"goal\") ]", "P = ? [ F (\"goal\") ]", "Pmax = ? [ F (\"goal\") ]");
                setLearningSettings(2, 50, 20000);
// ===== (1) web_3tier_Kfe6_Kapp6_Kdb4.prism =====
// Sum(fe*) = 0.86  -> 0.14 fallback-to-fail at FE hub
                this.parameterValues.addValue("fe1", 0.17);
                this.parameterValues.addValue("fe2", 0.16);
                this.parameterValues.addValue("fe3", 0.15);
                this.parameterValues.addValue("fe4", 0.14);
                this.parameterValues.addValue("fe5", 0.13);
                this.parameterValues.addValue("fe6", 0.11);

// Sum(ap*) = 0.78  -> 0.22 fallback-to-fail at App hub
                this.parameterValues.addValue("ap1", 0.16);
                this.parameterValues.addValue("ap2", 0.15);
                this.parameterValues.addValue("ap3", 0.14);
                this.parameterValues.addValue("ap4", 0.12);
                this.parameterValues.addValue("ap5", 0.11);
                this.parameterValues.addValue("ap6", 0.10);

// Sum(db*) = 0.78  -> 0.22 fallback-to-fail at DB hub
                this.parameterValues.addValue("db1", 0.24);
                this.parameterValues.addValue("db2", 0.22);
                this.parameterValues.addValue("db3", 0.18);
                this.parameterValues.addValue("db4", 0.14);

// Within-tier success once a node is picked (high but < 1)
                this.parameterValues.addValue("qFe", 0.97);
                this.parameterValues.addValue("qAp", 0.96);
                this.parameterValues.addValue("qDb", 0.95);
            }

            case PNUELI_ZUCK -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/pnueli-zuck.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ !\"cs\" U \"contend\" ]", "Pmaxmax = ? [ !\"cs\" U \"contend\" ]", "P = ? [ !\"cs\" U \"contend\" ]", "Pmax = ? [ !\"cs\" U \"contend\" ]");
                setLearningSettings(2, 50, 20000);

                addParameters("p_fast", 0.3);

            }

            case CROWDS -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/crowd.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F (observe0 > 1) ]", "Pmaxmax = ? [ F (observe0 > 1) ]", "P = ? [ F (observe0 > 1) ]", "Pmax = ? [ F (observe0 > 1) ]");
                setLearningSettings(2, 50, 100000);

                addParameters(
                        "PF", 0.8,
                        "badC", 0.091
                );

            }

            case CROWDS_PARAM -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/crowd_param.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F (observe0 > 1) ]", "Pmaxmax = ? [ F (observe0 > 1) ]", "P = ? [ F (observe0 > 1) ]", "Pmax = ? [ F (observe0 > 1) ]");
                setLearningSettings(2, 50, 1000000);

                addParameters(
                        "CrowdSize", 2,
                        "PF", 0.8,
                        "badC", 0.091,
                        "p_half_1", 1.0 / 2.0,
                        "p_half_2", 1.0 / 2.0,
                        "p_third_1", 1.0 / 3.0,
                        "p_third_2", 1.0 / 3.0,
                        "p_third_3", 1.0 / 3.0,
                        "p_fourth", 1.0 / 4.0,
                        "p_fith", 1.0 / 5.0,
                        "p_tenth", 1.0 / 10.0,
                        "p_fifteenth", 1.0 / 15.0,
                        "p_twenty", 1.0 / 20.0
                );

            }

            case BRP -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/brp.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F s=5 & srep=2 ]", "Pmaxmax = ? [ F s=5 & srep=2 ]", "P = ? [ F s=5 & srep=2 ]", "Pmax = ? [ F s=5 & srep=2 ]");
                setLearningSettings(2, 50, 100000);

                addParameters(
                        "pL", 0.69,
                        "pK", 0.6
                );

            }

            case EGL -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/egl.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F !\"knowA\" & \"knowB\" ]", "Pmaxmax = ? [ F !\"knowA\" & \"knowB\" ]", "P = ? [F !\"knowA\" & \"knowB\" ]", "Pmax = ? [ F !\"knowA\" & \"knowB\" ]");
                setLearningSettings(2, 50, 100000);

                addParameters(
                        "p_1", 0.5,
                        "p_2", 0.5
                );
            }

            case NAND -> {
                setModelFiles("../parametric_convex_models/bisimulation_models/nand.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F s=4 & z/5<0.1 ]", "Pmaxmax = ? [ F s=4 & z/5<0.1 ]", "P = ? [ F s=4 & z/5<0.1 ]", "Pmax = ? [ F s=4 & z/5<0.1 ]");
                setLearningSettings(2, 50, 100000);

                addParameters(
                        "prob1", 0.9,
                        "perr", 0.02
                );
            }


            case ENGAGEMENT_ADAPTIVE -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/engagement_adaptive.prism");
                setSpecsWithInverse(Type.REWARD, "Rminmax = ? [ F (\"purchase\" | \"churn\") ]", "Rminmin = ? [ F (\"purchase\" | \"churn\") ]", "R = ? [ F (\"purchase\" | \"churn\") ]", "Rmin = ? [ F (\"purchase\" | \"churn\") ]", "Rmax = ? [ F (\"purchase\" | \"churn\") ]");
                setLearningSettings(2, 150, 20000);

                // Set Parameter Values
                addParameters(
                        "L", 50,
                        "theta1", 0.3,
                        "theta2", 0.4
                );
            }

            case ENGAGEMENT_ADAPTIVE_5 -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/engagement_adaptive_5.prism");
                setSpecsWithInverse(Type.REWARD, "Rminmax = ? [ F (\"purchase\" | \"churn\") ]", "Rminmin = ? [ F (\"purchase\" | \"churn\") ]", "R = ? [ F (\"purchase\" | \"churn\") ]", "Rmin = ? [ F (\"purchase\" | \"churn\") ]", "Rmax = ? [ F (\"purchase\" | \"churn\") ]");

                int L = 100;

                setLearningSettings(2, L, 300000);

                // Set Parameter Values
                addParameters(
                        "L", L,
                        "theta1", 0.3,
                        "theta2", 0.2,
                        "theta3", 0.1,
                        "theta4", 0.25
                );
            }

            case ENGAGEMENT_ADAPTIVE_10 -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/engagement_adaptive_10.prism");
                setSpecs(Type.REWARD, "Rminmax = ? [ F (\"purchase\" | \"churn\") ]", "Rminmin = ? [ F (\"purchase\" | \"churn\") ]", "R = ? [ F (\"purchase\" | \"churn\") ]", "Rmin = ? [ F (\"purchase\" | \"churn\") ]");
                setLearningSettings(2, 50, 20000);

                // Set Parameter Values
                addParameters(
                        "L", 20,
                        "theta1", 0.08,
                        "theta2", 0.1,
                        "theta3", 0.11,
                        "theta4", 0.05,
                        "theta5", 0.04,
                        "theta6", 0.06,
                        "theta7", 0.12,
                        "theta8", 0.14,
                        "theta9", 0.02
                );
            }

            case ENGAGEMENT_ADAPTIVE_100 -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/engagement_adaptive_100.prism");
                setSpecs(Type.REWARD, "Rminmax = ? [ F (\"purchase\" | \"churn\") ]", "Rminmin = ? [ F (\"purchase\" | \"churn\") ]", "R = ? [ F (\"purchase\" | \"churn\") ]", "Rmin = ? [ F (\"purchase\" | \"churn\") ]");
                setLearningSettings(2, 50, 20000);

                // Set Parameter Values
                this.identParameters.addValue("L", 300);
                this.parameterValues.addValue("L", 300);
                for (int i = 1; i < 100; i++) {
                    this.parameterValues.addValue("theta" + i, 0.009);
                }
            }

            case ENGAGEMENT_ADAPTIVE_1000 -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/engagement_adaptive_1000.prism");
                setSpecs(Type.REWARD, "Rminmax = ? [ F (\"purchase\" | \"churn\") ]", "Rminmin = ? [ F (\"purchase\" | \"churn\") ]", "R = ? [ F (\"purchase\" | \"churn\") ]", "Rmin = ? [ F (\"purchase\" | \"churn\") ]");
                setLearningSettings(2, 50, 20000);

                // Set Parameter Values
                this.identParameters.addValue("L", 20);
                this.parameterValues.addValue("L", 20);
                for (int i = 0; i < 1000; i++) {
                    this.parameterValues.addValue("theta" + i, 0.0009);
                }
            }



            case GLIDER -> {
                setModelFiles("../parametric_convex_models/polynomial_mdps/glider.prism");
                setSpecsWithInverse(Type.REWARD, "Rminmax = ? [ F \"goal\"]", "Rminmin = ? [ F \"goal\"]", "R = ? [ F \"goal\"]", "Rmin = ? [ F \"goal\"]", "Rmax = ? [ F \"goal\"]");
                setLearningSettings(2, 100, 200000);

                // Set Parameter Values
                addParameters(
                        "w", 105,
                        "h", 99,
                        "theta_h", 0.3,
                        "theta_v", 0.7
                );
            }

            case BETTING_GAME_PARALLEL -> {
                setModelFiles("../parametric_convex_models/polynomial_mdps/bet_parallel.prism");
                setSpecsWithInverse(Type.REWARD, "Rmaxmin=? [F \"done\"]", "Rmaxmax=? [F \"done\"]", "R=? [F \"done\"]", "Rmax=? [F \"done\"]", "Rmin=? [F \"done\"]");

//                this.robustSpec = "Pmaxmin=? [F money + money2 >= 25]";
//                this.optimisticSpec = "Pmaxmax=? [F money + money2 >= 25]";
//                this.dtmcSpec = "P=? [F money + money2 >= 25]";
//                this.spec = "Pmax=? [F money + money2 >= 25]";
//                this.type = Type.REACH;

                int n = 6;

                setLearningSettings(2, n, 100000);

                // Set Parameter Values
                addParameters(
                        "n", n,
                        "p_1", 0.55,
                        "p_2", 0.53
                );
            }

            case KEY_DOOR_MAZE -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/key-door-maze.prism");
                setSpecs(Type.REWARD, "Rminmax = ? [ F \"goal\" ]", "Rminmin = ? [ F \"goal\" ]", "R=? [ F \"goal\" ]", "Rmin = ? [ F\"goal\" ]");

//                this.robustSpec = "Pmaxmin = ? [ !\"storm\" U \"goal\" ]";
//                this.optimisticSpec = "Pmaxmax = ? [ !\"storm\" U \"goal\" ]";
//                this.dtmcSpec = "P=? [ !\"storm\" U \"goal\" ]";
//                this.spec = "Pmax = ? [ !\"storm\" U \"goal\" ]";
//                this.type = Type.REACH;

                setLearningSettings(3, 200, 1000000);

                // Set Parameter Values
                addParameters(
                        "theta1", 0.11,
                        "theta2", 0.2,
                        "theta3", 0.15,
                        "theta4", 0.3
                );
            }

            case EPIDEMIC -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/epidemic.prism");
                setSpecs(Type.REACH, "Pminmax = ? [ !\"outbreak\" U \"eradicated\" ]", "Pminmin = ? [ !\"outbreak\" U \"eradicated\" ]", "P = ? [ !\"outbreak\" U \"eradicated\" ]", "Pmin = ? [ !\"outbreak\" U \"eradicated\" ]");
                setLearningSettings(2, 400, 20000);

                // Set Parameter Values
                addParameters(
                        "theta1", 0.45,
                        "theta2", 0.2,
                        "theta3", 0.1,
                        "theta4", 0.15
                );

            }

            case TEST_DTMC -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/test_dtmc.prism");
                setSpecs(Type.REACH, "Pmaxmin = ? [ F (\"goal\") ]", "Pmaxmax = ? [ F (\"goal\") ]", "P = ? [ F (\"goal\") ]", "Pmax = ? [ F (\"goal\") ]");
                setLearningSettings(2, 3, 20000);

                // Set Parameter Values
                addParameters(
                        "theta1", 0.6,
                        "theta2", 0.2
                );
            }


            case DRONE_MIXTURE -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/drone_mixture.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [!crash U target]", "Pmaxmax=? [!crash U target]", "P=? [!crash U target]", "Pmax=? [!crash U target]", "Pmin=? [!crash U target]");
                setLearningSettings(2, 300, 1000000);


                // Set Parameter Values
                addParameters(
                        "maxX", 32,
                        "maxY", 32,
                        "maxZ", 32,
                        "theta1", 0.1,
                        "theta2", 0.2,
                        "theta3", 0.15,
                        "theta4", 0.3
                );
            }

            case DRONE_MIXTURE_STEPS -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/drone_mixture_steps.prism");
                setSpecs(Type.REWARD, "Rminmax=? [F target]", "Rminmin=? [F target]", "R=? [F target]", "Rmin=? [F target]");
                setLearningSettings(2, 100, 1000000);

                // Set Parameter Values
                addParameters(
                        "theta1", 0.1,
                        "theta2", 0.2,
                        "theta3", 0.15,
                        "theta4", 0.3
                );
            }

            case SAV2-> {
                setModelFiles("../parametric_convex_models/mixture_mdps/sav_mixture.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [!(\"Crash\") U (\"Target\")]", "Pmaxmax=? [!(\"Crash\") U (\"Target\")]", "P=? [!(\"Crash\") U (\"Target\")]", "Pmax=? [!(\"Crash\") U (\"Target\")]", "Pmin=? [!(\"Crash\") U (\"Target\")]");

                // Set Parameter Values
                addParameters(
                        "Xsize", 25,
                        "Ysize", 25,
                        "theta1", 0.39,
                        "theta2", 0.2,
                        "theta3", 0.15
                );
            }

            case SAV2_ADAPTIVE-> {
                setModelFiles("../parametric_convex_models/mixture_mdps/sav_mixture_adaptive.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [!(\"Crash\") U (\"Target\")]", "Pmaxmax=? [!(\"Crash\") U (\"Target\")]", "P=? [!(\"Crash\") U (\"Target\")]", "Pmax=? [!(\"Crash\") U (\"Target\")]", "Pmin=? [!(\"Crash\") U (\"Target\")]");

                this.max_episode_length = 100;

                // Set Parameter Values
                addParameters(
                        "Xsize", 25,
                        "Ysize", 25,
                        "theta1", 0.4,
                        "theta2", 0.2,
                        "theta3", 0.15
                );
            }

            case SAV2_ADAPTIVE_5-> {
                setModelFiles("../parametric_convex_models/mixture_mdps/sav_mixture_adaptive_5.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [!(\"Crash\") U (\"Target\")]", "Pmaxmax=? [!(\"Crash\") U (\"Target\")]", "P=? [!(\"Crash\") U (\"Target\")]", "Pmax=? [!(\"Crash\") U (\"Target\")]", "Pmin=? [!(\"Crash\") U (\"Target\")]");

                this.max_episode_length = 50;
                this.maxVIIters = 100000;

                // Set Parameter Values
                addParameters(
                        "Xsize", 10,
                        "Ysize", 10,
                        "theta1", 0.4,
                        "theta2", 0.2,
                        "theta3", 0.15,
                        "theta4", 0.14
                );
            }

            case SAV2_ADAPTIVE_100-> {
                setModelFiles("../parametric_convex_models/mixture_mdps/sav_mixture_adaptive_100.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [!(\"Crash\") U (\"Target\")]", "Pmaxmax=? [!(\"Crash\") U (\"Target\")]", "P=? [!(\"Crash\") U (\"Target\")]", "Pmax=? [!(\"Crash\") U (\"Target\")]", "Pmin=? [!(\"Crash\") U (\"Target\")]");

                // Set Parameter Values

                // Set Parameter Values
                this.identParameters.addValue("Xsize", 12);
                this.identParameters.addValue("Ysize", 12);
                this.parameterValues.addValue("Xsize", 12);
                this.parameterValues.addValue("Ysize", 12);

                for (int i = 0; i < 100; i++) {
                    this.parameterValues.addValue("theta" + i, 0.009);
                }

            }

            case AIRCRAFT_MIXTURE_ONEMOD -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/aircraft_mixture_onemod.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [!collision U \"goal\"]", "Pmaxmax=? [!collision U \"goal\"]", "P=?  [!collision U \"goal\"]", "Pmax=? [!collision U \"goal\"]", "Pmin=? [!collision U \"goal\"]");

                // Set Parameter Values
                addParameters(
                        "maxX", 50,
                        "maxY", 10,
                        "theta1", 0.369,
                        "theta2", 0.2,
                        "theta3", 0.3
                );
            }

            case AIRCRAFT_MIXTURE_ONEMOD_ADAPTIVE -> {
                setModelFiles("../parametric_convex_models/mixture_mdps/aircraft_mixture_onemod_adaptive.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [!collision U \"goal\"]", "Pmaxmax=? [!collision U \"goal\"]", "P=?  [!collision U \"goal\"]", "Pmax=? [!collision U \"goal\"]", "Pmin=? [!collision U \"goal\"]");

                // Set Parameter Values
                addParameters(
                        "maxX", 30,
                        "maxY", 10,
                        "theta1", 0.4,
                        "theta2", 0.2,
                        "theta3", 0.15
                );
            }

            case AIRCRAFT_MIXTURE_POSITION-> {
                setModelFiles("../parametric_convex_models/aircraft_pos.prism");
                setSpecsWithInverse(Type.REACH, "Pmaxmin=? [!collision U \"goal\"]", "Pmaxmax=? [!collision U \"goal\"]", "P=?  [!collision U \"goal\"]", "Pmax=? [!collision U \"goal\"]", "Pmin=? [!collision U \"goal\"]");

                int maxx = 20;
                int maxy = 10;

                setLearningSettings(2, maxx, 100000);

                // Set Parameter Values
                addParameters(
                        "maxX", maxx,
                        "maxY", maxy,
                        "theta1", 0.4,
                        "theta2", 0.2,
                        "theta3", 0.15,
                        "theta4", 0.12
                );
            }

            case DICE_2 -> {
                setModelFiles("../models/dice/dice2.prism", "../models/dice/dice2_certain.prism");
                setSpecs(Type.REWARD, "Rminmax=? [ F (s1=7 & s2 = 7) ]", "Rminmin=? [ F (s1=7 & s2 = 7) ]", "R=? [ F (s1=7 & s2 = 7) ]", "Rmin=? [ F (s1=7 & s2 = 7) ]");

                // Set Parameter Values
                addParameters(
                        "p", 0.4,
                        "eps", 0.1
                );
            }

            case DICE_3 -> {
                setModelFiles("../models/dice/dice3.prism", "../models/dice/dice3_certain.prism");
                setSpecs(Type.REWARD, "Rminmax=? [ F (s1=7 & s2 = 7 & s3 = 7) ]", "Rminmin=? [ F (s1=7 & s2 = 7 & s3 = 7) ]", "R=? [ F (s1=7 & s2 = 7 & s3 = 7) ]", "Rmin=? [ F (s1=7 & s2 = 7 & s3 = 7) ]");

                // Set Parameter Values
                addParameters(
                        "p", 0.22,
                        "q", 0.4,
                        "eps", 0.1
                );
            }

            case HERMAN_3 -> {
                setModelFiles("../models/herman/herman7.prism", "../models/herman/herman7_certain.prism");
                this.robustSpec = "Rminmax=? [ F \"stable\"]";
                this.dtmcSpec = "R=? [ F \"stable\" ]";

                // Set Parameter Values
                addParameters(
                        "p", 0.3333333,
                        "eps", 0.1
                );
            }

            case DRONE -> {
                setModelFiles("../models/drone/drone.prism", "../models/drone/drone_certain.prism");
                setSpecs(Type.REACH, "Pmaxmin=? [!crash U target]", "Pmaxmax=? [!crash U target]", "P=? [!crash U target]", "Pmax=? [!crash U target]");

                // Set Parameter Values
                addParameters(
                        "p", 0.2,
                        "eps", 0.1
                );
            }

            case DRONE_MULTI -> {
                setModelFiles("../models/drone_multislip/drone.prism", "../models/drone_multislip/drone_certain.prism");
                setSpecs(Type.REACH, "Pmaxmin=? [!crash U target]", "Pmaxmax=? [!crash U target]", "P=? [!crash U target]", "Pmax=? [!crash U target]");
                setLearningSettings(5, 50, 3500);

                // Set Parameter V alues
                addParameters(
                        "MAXX", 5,
                        "MAXY", 5,
                        "MAXZ", 5,
                        "p", 0.3,
                        "eps", 0.029
                );
            }

            case DRONE_MULTI_2 -> {
                setModelFiles("../models/drone_multislip/drone_2.prism", "../models/drone_multislip/drone_certain.prism");
                this.robustSpec = "Pmaxmin=? [!crash U target]";
                this.dtmcSpec = "P=? [!crash U target]";

                // Set Parameter Values
                addParameters(
                        "p", 0.3,
                        "eps", 0.03
                );
            }


        }
        return this;
    }
}