package learning;

import prism.Prism;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line batch runner for PAC-CSG learning experiments.
 * Runs one or more seeds of a single configuration and appends one CSV row per run.
 *
 * Usage (from the repository root):
 *   PRISM_MAINCLASS=learning.RunPac prism/bin/prism \
 *     -model SAFE_RISKY -prop 4 -eps 0.1 -seeds 1-10 -explorer rmdp \
 *     -out prism-examples/csgs/learning/results/batch/safe_risky4.csv -logsubdir batch
 *
 * Options:
 *   -model NAME          case study enum name (e.g. SAFE_RISKY); or use -modelFile/-propsFile
 *   -modelFile PATH      explicit .prism model path (with -propsFile)
 *   -propsFile PATH      explicit .props path
 *   -prop N              1-based property index (default: case study default)
 *   -eps X               epsilon (default: rMax/10)
 *   -delta X             confidence (default 0.05)
 *   -seeds SPEC          comma list and/or ranges, e.g. "1,2,5" or "1-10" (default: 1)
 *   -explorer NAME       rmdp | optimistic | uniform | roundrobin  (default rmdp)
 *   -const n=v,n=v       model/property constants (ints or doubles), e.g. "k=5" or "HB=7,N=8"
 *   -out PATH            results CSV to append to (default results/batch/<model><prop>.csv)
 *   -logsubdir NAME      subdir under logs/ for per-episode logs (default "batch")
 *   -valueGapEvery N     solve empirical game every N episodes and log true value gap (default 0 = off)
 *   -maxSamples N        sample budget for robustness experiments (default 10000)
 *   -robustness          treat as robustness experiment (sample budget cap)
 *   -solver NAME         SMT solver (default Yices)
 *   -exportStrat         export learned strategies to files (default off in batch mode)
 */
public class RunPac {

    private static final String RESULTS_ROOT = "./prism-examples/csgs/learning/results/";

    private static final String CSV_HEADER =
            "timestamp,model,propertyIndex,consts,epsilon,confidence,explorer,seed,valueGapEvery," +
            "episodes,totalSamples,totalTransitions,nMin,deltaT,runtimeMs," +
            "trueFound,trueValue,robustFound,robustValue,robustTrueValue,robustValueGap," +
            "pointFound,pointValue,pointTrueValue,pointValueGap";

    public static void main(String[] args) throws Exception {
        String modelName = null, modelFile = null, propsFile = null;
        int prop = -1;
        double eps = -1.0, delta = 0.05;
        List<Integer> seeds = new ArrayList<>();
        ExplorationPolicy explorer = ExplorationPolicy.RMDP;
        String constSpec = "";
        String out = null;
        String logSubdir = "batch";
        int valueGapEvery = 0;
        int maxSamples = 10000;
        boolean robustness = false;
        String solver = "Yices";
        boolean exportStrat = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-model" -> modelName = args[++i];
                case "-modelFile" -> modelFile = args[++i];
                case "-propsFile" -> propsFile = args[++i];
                case "-prop" -> prop = Integer.parseInt(args[++i]);
                case "-eps" -> eps = Double.parseDouble(args[++i]);
                case "-delta" -> delta = Double.parseDouble(args[++i]);
                case "-seeds" -> seeds.addAll(parseSeeds(args[++i]));
                case "-explorer" -> explorer = ExplorationPolicy.fromString(args[++i]);
                case "-const" -> constSpec = args[++i];
                case "-out" -> out = args[++i];
                case "-logsubdir" -> logSubdir = args[++i];
                case "-valueGapEvery" -> valueGapEvery = Integer.parseInt(args[++i]);
                case "-maxSamples" -> maxSamples = Integer.parseInt(args[++i]);
                case "-robustness" -> robustness = true;
                case "-solver" -> solver = args[++i];
                case "-exportStrat" -> exportStrat = true;
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (modelName == null && (modelFile == null || propsFile == null)) {
            throw new IllegalArgumentException("Provide -model NAME or both -modelFile and -propsFile");
        }
        if (seeds.isEmpty()) seeds.add(1);

        String modelId = (modelName != null) ? modelName
                : Paths.get(modelFile).getFileName().toString().replaceFirst("\\.[^.]+$", "");

        for (int seed : seeds) {
            Experiment ex = (modelName != null)
                    ? new Experiment(Experiment.CaseStudy.valueOf(modelName.toUpperCase()))
                    : new Experiment(modelFile, propsFile, prop > 0 ? prop : 1);
            if (prop > 0) ex.propertyIndex = prop;
            ex.epsilon = eps;
            ex.confidence = delta;
            ex.maxNumSamples = maxSamples;
            if (robustness) ex.robustnessExperiment = true;
            ex.setSolverString(solver);
            applyConsts(ex, constSpec);

            String outPath = (out != null) ? out
                    : RESULTS_ROOT + "batch/" + modelId.toLowerCase() + ex.propertyIndex + ".csv";

            String runLabel = explorer + "_s" + seed + (constSpec.isEmpty() ? "" : "_" + constSpec.replace("=", "").replace(",", "_"));
            System.out.println("\n================ RUN " + modelId + " prop=" + ex.propertyIndex
                    + " explorer=" + explorer + " seed=" + seed
                    + (constSpec.isEmpty() ? "" : " consts=" + constSpec) + " ================");

            Prism prism = new Prism();
            prism.initialise();
            prism.useNative();

            try {
                Experiment.PacRunSpec spec = ex.buildPacRunSpec(prism);
                PACLearner learner = new PACLearner(prism, seed, true);
                learner.setExplorationPolicy(explorer);
                learner.setValueGapEvery(valueGapEvery);
                learner.setRunLabel(runLabel);

                long start = System.nanoTime();
                PACLearner.PacResult res = learner.runPacLoop(spec, ex.modelFile, ex.propertyIndex, ex.robustnessExperiment, logSubdir);
                long duration = System.nanoTime() - start;

                if (exportStrat) {
                    learner.printResult(duration, res, true, ex.modelFile, ex.propertyIndex, ex.robustnessExperiment, logSubdir);
                }
                PACLearner.RunSummary rs = learner.summarise(duration, res);
                appendRow(outPath, modelId, ex.propertyIndex, constSpec, spec.epsilon, delta, explorer, seed, valueGapEvery, rs);
                System.out.println("RESULT " + modelId + ex.propertyIndex + " explorer=" + explorer + " seed=" + seed
                        + ": episodes=" + rs.episodes + " samples=" + rs.totalSamples + " transitions=" + rs.totalTransitions
                        + " deltaT=" + rs.deltaT + " runtimeMs=" + String.format("%.1f", rs.runtimeMs)
                        + " robustValue=" + rs.robustValue + " robustValueGap=" + rs.robustValueGap);
            } catch (Exception e) {
                System.err.println("Run failed (model=" + modelId + ", seed=" + seed + "): " + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    prism.closeDown();
                } catch (Exception ignored) {
                }
            }
        }
        System.out.println("\nAll runs finished.");
    }

    private static List<Integer> parseSeeds(String spec) {
        List<Integer> seeds = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-', 1); // allow negative? seeds are positive; '-' after first char = range
            if (dash > 0) {
                int lo = Integer.parseInt(part.substring(0, dash));
                int hi = Integer.parseInt(part.substring(dash + 1));
                for (int s = lo; s <= hi; s++) seeds.add(s);
            } else {
                seeds.add(Integer.parseInt(part));
            }
        }
        return seeds;
    }

    private static void applyConsts(Experiment ex, String constSpec) {
        if (constSpec == null || constSpec.isEmpty()) return;
        for (String pair : constSpec.split(",")) {
            String[] kv = pair.split("=");
            if (kv.length != 2) throw new IllegalArgumentException("Bad -const entry: " + pair);
            String name = kv[0].trim();
            String val = kv[1].trim();
            try {
                ex.setSingleValue(name, Integer.parseInt(val));
            } catch (NumberFormatException e) {
                ex.setSingleValue(name, Double.parseDouble(val));
            }
        }
    }

    private static synchronized void appendRow(String outPath, String model, int prop, String consts,
                                               double eps, double delta, ExplorationPolicy explorer, int seed,
                                               int valueGapEvery, PACLearner.RunSummary rs) throws Exception {
        Path p = Paths.get(outPath);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        boolean writeHeader = !Files.exists(p) || Files.size(p) == 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(p.toFile(), true))) {
            if (writeHeader) w.println(CSV_HEADER);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            w.println(String.join(",",
                    ts, model, String.valueOf(prop),
                    consts.isEmpty() ? "-" : consts.replace(",", ";"),
                    String.valueOf(eps), String.valueOf(delta),
                    explorer.toString(), String.valueOf(seed), String.valueOf(valueGapEvery),
                    String.valueOf(rs.episodes), String.valueOf(rs.totalSamples), String.valueOf(rs.totalTransitions),
                    String.valueOf(rs.nMin), String.valueOf(rs.deltaT), String.format("%.1f", rs.runtimeMs),
                    String.valueOf(rs.trueFound), String.valueOf(rs.trueValue),
                    String.valueOf(rs.robustFound), String.valueOf(rs.robustValue),
                    String.valueOf(rs.robustTrueValue), String.valueOf(rs.robustValueGap),
                    String.valueOf(rs.pointFound), String.valueOf(rs.pointValue),
                    String.valueOf(rs.pointTrueValue), String.valueOf(rs.pointValueGap)));
        }
    }
}
