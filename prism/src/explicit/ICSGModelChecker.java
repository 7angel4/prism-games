package explicit;

import explicit.rewards.CSGRewards;
import parser.ast.Coalition;
import prism.PrismComponent;
import prism.PrismException;
import strat.CSGStrategy;
import strat.ICSGStrategy;
import strat.Strategy;

import java.util.*;

public class ICSGModelChecker extends CSGModelChecker {
//    protected CSGModelChecker mcCSG = null;

    /**
     * Create a new IMDPModelChecker, inherit basic state from parent (unless null).
     */
    public ICSGModelChecker(PrismComponent parent) throws PrismException
    {
        super(parent);
//        mcCSG = new CSGModelChecker(this);
//        mcCSG.inheritSettings(this);
    }



    @Override
    protected Strategy<?> getStrategy(CSG<?> icsg, List<List<List<Map<BitSet, Double>>>> lstrat, BitSet no, BitSet yes, BitSet inf, CSGStrategy.CSGStrategyType type) {
        return new ICSGStrategy((ICSG<Double>) icsg, lstrat, no, yes, inf, type);
    }

    public ModelCheckerResult computeReachProbs(ICSG<Double> icsg, BitSet target, MinMax minMax, int bound, Coalition coalition) throws PrismException {
        icsg.checkLowerBoundsArePositive();
        icsg.checkForDeadlocks(target);
        return super.computeReachProbs(icsg.getIntervalModel(), target, minMax.isMin1(), minMax.isMin2(), bound, coalition);
    }

    public ModelCheckerResult computeNextProbs(ICSG<Double> icsg, BitSet target, MinMax minMax) throws PrismException {
        icsg.checkLowerBoundsArePositive();
        icsg.checkForDeadlocks(target);
        return super.computeNextProbs(icsg, target, minMax.isMin1(), minMax.isMin2(), minMax.getCoalition());
    }

    public ModelCheckerResult computeUntilProbs(ICSG<Double> icsg, BitSet remain, BitSet target, int bound, MinMax minmax)
            throws PrismException {
        icsg.checkLowerBoundsArePositive();
        icsg.checkForDeadlocks(target);
        return super.computeUntilProbs(icsg.getIntervalModel(), remain, target, bound, minmax.isMin1(), minmax.isMin2(), minmax.getCoalition());
    }

    public ModelCheckerResult computeUntilProbs(ICSG<Double> icsg, BitSet remain, BitSet target, MinMax minmax) throws PrismException {
        return computeUntilProbs(icsg, remain, target, maxIters, minmax);
    }

    public ModelCheckerResult computeBoundedUntilProbs(ICSG<Double> icsg, BitSet remain, BitSet target, int k, MinMax minmax)
            throws PrismException
    {
        return computeUntilProbs(icsg.getIntervalModel(), remain, target, k, minmax.isMin1(), minmax.isMin2(), minmax.getCoalition());
    }

    public ModelCheckerResult computeReachRewardsCumulative(ICSG<Double> icsg, CSGRewards<Double> rewards, BitSet target, MinMax minMax) throws PrismException {
        icsg.checkLowerBoundsArePositive();
        icsg.checkForDeadlocks(target);
        return super.computeReachRewardsCumulative(icsg, minMax.getCoalition(), rewards, target, minMax.isMin1(), minMax.isMin2(), false);
    }

    public ModelCheckerResult computeReachRewardsInfinity(ICSG<Double> icsg, CSGRewards<Double> rewards, BitSet target, MinMax minMax)
            throws PrismException {
        icsg.checkLowerBoundsArePositive();
        icsg.checkForDeadlocks(target);
        return super.computeReachRewardsInfinity(icsg, minMax.getCoalition(), rewards, target, minMax.isMin1(), minMax.isMin2());
    }

    public ModelCheckerResult computeReachRewards(ICSG<Double> icsg, CSGRewards<Double> rewards, BitSet target, int unreachingSemantics, MinMax minMax) throws PrismException {
        // TODO: confirm that the case min1==min2 is not handled
        switch (unreachingSemantics) {
            case R_INFINITY:
                return computeReachRewardsInfinity(icsg, rewards, target, minMax);
            case R_CUMULATIVE:
                return computeReachRewardsCumulative(icsg, rewards, target, minMax);
            case R_ZERO:
                throw new PrismException("F0 is not yet supported for CSGs.");
            default:
                throw new PrismException("Unknown semantics for runs unreaching the target in CSGModelChecker: " + unreachingSemantics);
        }
    }

    public ModelCheckerResult computeCumulativeRewards(ICSG<Double> icsg, CSGRewards<Double> rewards, int k, MinMax minMax)
            throws PrismException
    {
        icsg.checkLowerBoundsArePositive();
        return super.computeCumulativeRewards(icsg, rewards, minMax.getCoalition(), k, minMax.isMin1(), minMax.isMin2(), false);
    }

    public ModelCheckerResult computeTotalRewards(ICSG<Double> icsg, CSGRewards<Double> rewards, MinMax minMax)
            throws PrismException {
        icsg.checkLowerBoundsArePositive();
        return super.computeTotalRewards(icsg, rewards, minMax.isMin1(), minMax.isMin2(), minMax.getCoalition());
    }

}
