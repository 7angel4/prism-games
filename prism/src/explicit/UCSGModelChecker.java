package explicit;

import explicit.rewards.CSGRewards;
import parser.ast.Coalition;
import prism.PrismComponent;
import prism.PrismException;

import java.util.BitSet;

public class UCSGModelChecker extends ProbModelChecker
{
	// MDPModelChecker in order to use e.g. precomputation algorithms
	protected CSGModelChecker mcCSG = null;

	/**
	 * Create a new UCSGModelChecker, inherit basic state from parent (unless null).
	 */
	public UCSGModelChecker(PrismComponent parent) throws PrismException
	{
		super(parent);
		mcCSG = new CSGModelChecker(this);
		mcCSG.inheritSettings(this);
	}

	public ModelCheckerResult computeReachProbs(ICSG<Double> icsg, BitSet target, MinMax minMax, int bound, Coalition coalition) throws PrismException {
		// needed because UCSGModelChecker's settings are updated after construction in Prism.createModelCheckerExplicit
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		icsg.checkForDeadlocks(target);
		return mcCSG.computeReachProbs(icsg.getIntervalModel(), target, minMax.isMin1(), minMax.isMin2(), bound, coalition);
	}

	public ModelCheckerResult computeNextProbs(ICSG<Double> icsg, BitSet target, MinMax minMax) throws PrismException {
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		icsg.checkForDeadlocks(target);
		return mcCSG.computeNextProbs(icsg.getIntervalModel(), target, minMax.isMin1(), minMax.isMin2(), minMax.getCoalition());
	}

	public ModelCheckerResult computeUntilProbs(ICSG<Double> icsg, BitSet remain, BitSet target, int bound, MinMax minmax)
			throws PrismException {
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		icsg.checkForDeadlocks(target);
		return mcCSG.computeUntilProbs(icsg.getIntervalModel(), remain, target, bound, minmax.isMin1(), minmax.isMin2(), minmax.getCoalition());
	}

	public ModelCheckerResult computeUntilProbs(ICSG<Double> icsg, BitSet remain, BitSet target, MinMax minmax) throws PrismException {
		return computeUntilProbs(icsg, remain, target, maxIters, minmax);
	}

	public ModelCheckerResult computeBoundedUntilProbs(ICSG<Double> icsg, BitSet remain, BitSet target, int k, MinMax minmax)
			throws PrismException
	{
		return computeUntilProbs(icsg, remain, target, k, minmax);
	}

	public ModelCheckerResult computeReachRewardsCumulative(ICSG<Double> icsg, CSGRewards<Double> rewards, BitSet target, MinMax minMax) throws PrismException {
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		icsg.checkForDeadlocks(target);
		return mcCSG.computeReachRewardsCumulative(icsg.getIntervalModel(), minMax.getCoalition(), rewards, target, minMax.isMin1(), minMax.isMin2(), false);
	}

	public ModelCheckerResult computeReachRewardsInfinity(ICSG<Double> icsg, CSGRewards<Double> rewards, BitSet target, MinMax minMax)
			throws PrismException {
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		icsg.checkForDeadlocks(target);
		return mcCSG.computeReachRewardsInfinity(icsg.getIntervalModel(), minMax.getCoalition(), rewards, target, minMax.isMin1(), minMax.isMin2());
	}

	public ModelCheckerResult computeReachRewards(ICSG<Double> icsg, CSGRewards<Double> rewards, BitSet target, int unreachingSemantics, MinMax minMax) throws PrismException {
		// TODO: confirm that the case min1==min2 is not handled
		switch (unreachingSemantics) {
			case CSGModelChecker.R_INFINITY:
				return computeReachRewardsInfinity(icsg, rewards, target, minMax);
			case CSGModelChecker.R_CUMULATIVE:
				return computeReachRewardsCumulative(icsg, rewards, target, minMax);
			case CSGModelChecker.R_ZERO:
				throw new PrismException("F0 is not yet supported for CSGs.");
			default:
				throw new PrismException("Unknown semantics for runs unreaching the target in CSGModelChecker: " + unreachingSemantics);
		}
	}

	public ModelCheckerResult computeCumulativeRewards(ICSG<Double> icsg, CSGRewards<Double> rewards, int k, MinMax minMax)
			throws PrismException
	{
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		return mcCSG.computeCumulativeRewards(icsg.getIntervalModel(), rewards, minMax.getCoalition(), k, minMax.isMin1(), minMax.isMin2(), false);
	}

	public ModelCheckerResult computeTotalRewards(ICSG<Double> icsg, CSGRewards<Double> rewards, MinMax minMax)
			throws PrismException {
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		return mcCSG.computeTotalRewards(icsg.getIntervalModel(), rewards, minMax.isMin1(), minMax.isMin2(), minMax.getCoalition());
	}
}
