package learning;

/**
 * Exploration strategy used to collect trajectories in the PAC learning loop.
 * RMDP is the paper's algorithm; the others are baselines for ablation:
 * <ul>
 *   <li>{@link #RMDP} — robust (pessimistic) exploration RMDP (default; the paper's Algorithm 1).</li>
 *   <li>{@link #OPTIMISTIC} — same exploration RMDP, but optimistic over the uncertainty set.</li>
 *   <li>{@link #UNIFORM} — uniform-random joint actions at every state.</li>
 *   <li>{@link #ROUND_ROBIN} — cycle through the least-sampled unknown (s,a) slots,
 *       navigating to each target via the point-estimate MDP.</li>
 * </ul>
 */
public enum ExplorationPolicy {
    RMDP,
    OPTIMISTIC,
    UNIFORM,
    ROUND_ROBIN;

    public static ExplorationPolicy fromString(String s) {
        switch (s.toLowerCase().replace("-", "").replace("_", "")) {
            case "rmdp": case "robust": case "pessimistic": return RMDP;
            case "optimistic": case "opt": return OPTIMISTIC;
            case "uniform": case "random": return UNIFORM;
            case "roundrobin": case "rr": return ROUND_ROBIN;
            default: throw new IllegalArgumentException("Unknown exploration policy: " + s);
        }
    }

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
