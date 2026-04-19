package explicit;

import explicit.rewards.CSGRewards;
import prism.PlayerInfoOwner;
import prism.PrismException;

import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public interface UCSG<Value> extends NondetModel<Value>, UMDP<Value>, PlayerInfoOwner {
    default void checkLowerBoundsArePositive() throws PrismException {
    }
    default void checkForDeadlocks(BitSet target) throws PrismException {
    }

    BitSet[] getIndexes();

    int[] getIndexes(int s, int i);

    BitSet getIndexesForPlayer(int s, int p);

    int getIdleForPlayer(int p);

    Distribution<Double> getDoubleChoice(int s, int i, double val[]);

    default Iterator<Map.Entry<Integer, Double>> getDoubleTransitionsIterator(int s, int i, double val[]) {
        return getDoubleChoice(s, i, val).iterator();
    }

    default void forEachDoubleTransition(int s, int i, double[] val, MDP.TransitionConsumer<Double> c)
    {
        for (Iterator<Map.Entry<Integer, Double>> it = getDoubleTransitionsIterator(s, i, val); it.hasNext(); ) {
            Map.Entry<Integer, Double> e = it.next();
            c.accept(s, e.getKey(), e.getValue());
        }
    }

    CSG<?> getCSGModel();   // core hook

    default Distribution<?> getChoice(int s, int i) {
        return getCSGModel().getChoice(s, i);
    }

    boolean filterNEforRNE(double[][] eqVal,
                           List<List<Map<BitSet, Double>>> strats,
                           List<CSGRewards<Double>> csgRewards,
                           BitSet[] coalitionIndexes,
                           int s,
                           boolean min,
                           double[][] val) throws PrismException;

    double[] findRNE(double[][] eqVal,
                     List<List<Map<BitSet, Double>>> strats,
                     List<CSGRewards<Double>> csgRewards,
                     BitSet[] coalitionIndexes,
                     int s,
                     boolean min,
                     double[][] val) throws PrismException;
}
