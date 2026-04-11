//==============================================================================
//
//	Copyright (c) 2023-
//	Authors:
//	* Dave Parker <david.parker@cs.ox.ac.uk> (University of Oxford)
//
//------------------------------------------------------------------------------
//
//	This file is part of PRISM.
//
//	PRISM is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version.
//
//	PRISM is distributed in the hope that it will be useful,
//	but WITHOUT ANY WARRANTY; without even the implied warranty of
//	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//	GNU General Public License for more details.
//
//	You should have received a copy of the GNU General Public License
//	along with PRISM; if not, write to the Free Software Foundation,
//	Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//==============================================================================

package explicit;

import prism.Evaluator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.BitSet;

/**
 * Exact L1-uncertain distribution around a nominal distribution.
 * The robust backup computes the support function over the L1 ball.
 */
public class UDistributionL1<Value> implements UDistribution<Value>
{
    /** Nominal transition frequencies / probabilities. */
    protected Distribution<Value> frequencies;

    /** L1 radius. Always treated numerically as a double. */
    protected double l1max;

    /**
     * Standard constructor.
     */
    public UDistributionL1(Distribution<Value> frequencies, double l1max)
    {
        this.frequencies = frequencies;
        this.l1max = l1max;
    }

    /**
     * Build the product L1-distribution from a list of marginals.
     * The supportArray must enumerate the product outcomes in the same
     * order used by the Cartesian-product recursion below.
     */
    public UDistributionL1(List<UDistributionL1<Value>> marginals, int[] supportArray)
    {
        if (marginals == null || marginals.isEmpty()) {
            throw new IllegalArgumentException("Must supply at least one marginal");
        }

        Evaluator<Value> eval = marginals.get(0).frequencies.getEvaluator();

        // Sum radii.
        double sumRadius = 0.0;
        List<List<Map.Entry<Integer, Value>>> factorEntries = new ArrayList<>(marginals.size());
        int expectedSupportSize = 1;

        for (UDistributionL1<Value> d : marginals) {
            sumRadius += d.l1max;

            List<Map.Entry<Integer, Value>> entries = new ArrayList<>();
            Iterator<Map.Entry<Integer, Value>> it = d.frequencies.iterator();
            while (it.hasNext()) {
                entries.add(it.next());
            }
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("A marginal distribution may not be empty");
            }

            factorEntries.add(entries);

            try {
                expectedSupportSize = Math.multiplyExact(expectedSupportSize, entries.size());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Support size overflow while building product L1 distribution", e);
            }
        }

        if (supportArray.length != expectedSupportSize) {
            throw new IllegalArgumentException(
                    "supportArray length (" + supportArray.length + ") does not match product support size (" + expectedSupportSize + ")"
            );
        }

        this.l1max = sumRadius;
        this.frequencies = new Distribution<>(eval);

        int[] cursor = new int[] { 0 };
        buildCartesianProduct(factorEntries, 0, eval.one(), cursor, supportArray, eval);

        if (cursor[0] != supportArray.length) {
            throw new IllegalStateException("Internal error while building product L1 distribution");
        }
    }

    private void buildCartesianProduct(
            List<List<Map.Entry<Integer, Value>>> factors,
            int depth,
            Value currentProb,
            int[] cursor,
            int[] supportArray,
            Evaluator<Value> eval
    ) {
        if (depth == factors.size()) {
            frequencies.add(supportArray[cursor[0]++], currentProb);
            return;
        }

        for (Map.Entry<Integer, Value> entry : factors.get(depth)) {
            Value nextProb = eval.multiply(currentProb, entry.getValue());
            buildCartesianProduct(factors, depth + 1, nextProb, cursor, supportArray, eval);
        }
    }

    @Override
    public boolean contains(int j)
    {
        return frequencies.contains(j);
    }

    @Override
    public boolean isSubsetOf(BitSet set)
    {
        return frequencies.isSubsetOf(set);
    }

    @Override
    public boolean containsOneOf(BitSet set)
    {
        return frequencies.containsOneOf(set);
    }

    @Override
    public Set<Integer> getSupport()
    {
        return frequencies.getSupport();
    }

    @Override
    public boolean isEmpty()
    {
        return frequencies.isEmpty();
    }

    @Override
    public int size()
    {
        return frequencies.size();
    }

    /**
     * Robust matrix-vector multiplication over the L1 ball.
     * Returns the worst-case expectation if minMax is minimizing uncertainty,
     * and the best-case expectation if minMax is maximizing uncertainty.
     */
    @Override
    public double mvMultUnc(double[] vect, MinMax minMax)
    {
        return L1SupportFunction.solve(frequencies, l1max, vect, minMax);
    }

    @Override
    public UDistribution<Value> copy()
    {
        Distribution<Value> frequenciesCopy = new Distribution<>(frequencies);
        return new UDistributionL1<>(frequenciesCopy, l1max);
    }

    @Override
    public UDistribution<Value> copy(int[] permut)
    {
        Distribution<Value> frequenciesCopy = new Distribution<>(frequencies, permut);
        return new UDistributionL1<>(frequenciesCopy, l1max);
    }

    @Override
    public String toString()
    {
        return "[" + frequencies + ", L1Max: " + l1max + "]";
    }
}