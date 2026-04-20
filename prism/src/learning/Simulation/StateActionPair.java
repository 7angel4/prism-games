package learning.Simulation;

/**
 * Small class for state-action pairs (s,a)
 */
public class StateActionPair
{
    private final int s;
    private final String action;
    private final int action_hash;

    public StateActionPair(int s, String action) {
        this.s = s;
        this.action = action;
        this.action_hash = action.hashCode();
    }


    public int getState() {
        return this.s;
    }

    public String getAction() {
        return this.action;
    }

    @Override
    public int hashCode() {
        // start with the state’s own hash…
        int h = Integer.hashCode(s);
        // mix in the action’s hash
        h = 31 * h + action_hash;
        return h;
    }

    @Override
    public boolean equals(Object o) {
        // 1) same object?
        if (this == o) return true;

        // 2) instanceof is null‑safe and avoids getClass()
        if (!(o instanceof StateActionPair other)) return false;

        // 3) quick reject if we’ve cached a different hash
        if (this.hashCode() != other.hashCode()) return false;

        // 4) direct field comparisons (no getters)
        return this.s == other.s
                && this.action.equals(other.action);
    }

    @Override
    public String toString() {
        return "(" + this.s + ", " + this.action + ")";
    }
}