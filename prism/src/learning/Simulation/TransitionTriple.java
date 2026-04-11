package learning.Simulation;

/**
 * Small class for transition triples (s,a,s')
 */
public class TransitionTriple {
    private int s;
    private String action;
    private int action_hash;
    private int successor;
    private final StateActionPair stateActionPair;

    public TransitionTriple(int s, String action, int successor) {
        this.s = s;
        this.action = action;
        this.action_hash = action.hashCode();
        this.successor = successor;
        this.stateActionPair = new StateActionPair(this.s, this.action);
    }

    public TransitionTriple setAll(int s, String action, int successor) {
        this.s = s;
        this.action = action;
        this.action_hash = action.hashCode();
        this.successor = successor;
        //this.stateActionPair = new StateActionPair(this.s, this.action);
        return this;
    }

    public int getState() {
        return this.s;
    }

    public String getAction() {
        return this.action;
    }

    public StateActionPair getStateAction() {
        return this.stateActionPair;
    }

    public int getSuccessor() {
        return this.successor;
    }

    @Override
    public int hashCode() {
        int h = s;
        h = 31*h + action_hash;
        h = 31*h + successor;
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null)
            return false;

        if (this.getClass() != o.getClass())
            return false;

        TransitionTriple other = (TransitionTriple) o;
        return (this.s == other.getState() && this.action.equals(other.getAction()) && this.successor == other.getSuccessor());
    }

    @Override
    public String toString() {
        return "(" + this.s + ", " + this.action + ", " + this.successor + ")";
    }
}