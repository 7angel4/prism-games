package explicit;

import java.util.Arrays;
import java.util.Map;

/**
 * Immutable transition row represented by a centre distribution and an L1 radius.
 */
public final class L1Transition
{
	private final int[] successors;
	private final double[] centre;
	private final double radius;

	public L1Transition(int[] successors, double[] centre, double radius)
	{
		if (successors.length != centre.length) {
			throw new IllegalArgumentException("Successor and probability arrays must have the same length");
		}
		this.successors = Arrays.copyOf(successors, successors.length);
		this.centre = Arrays.copyOf(centre, centre.length);
		this.radius = radius;
	}

	public static L1Transition fromDistribution(Distribution<Double> distr, double radius)
	{
		int n = distr.size();
		int[] succ = new int[n];
		double[] probs = new double[n];
		int i = 0;
		for (Map.Entry<Integer, Double> e : distr) {
			succ[i] = e.getKey();
			probs[i] = e.getValue();
			i++;
		}
		return new L1Transition(succ, probs, radius);
	}

	public int size()
	{
		return successors.length;
	}

	public int[] getSuccessors()
	{
		return Arrays.copyOf(successors, successors.length);
	}

	public double[] getCentre()
	{
		return Arrays.copyOf(centre, centre.length);
	}

	public double getRadius()
	{
		return radius;
	}
}

