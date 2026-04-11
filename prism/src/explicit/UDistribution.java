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

import java.util.BitSet;
import java.util.Set;

/**
 * Explicit representation of an uncertain probability distribution, i.e., a set of probability distributions.
 * This is a generic class where probabilities are of type {@code Value}.
 */
public interface UDistribution<Value>
{
    /**
     * Returns true if index {@code j} is in the support of the distribution.
     */
    boolean contains(int j);

    /**
     * Returns true if all indices in the support of the distribution are in the set.
     */
    boolean isSubsetOf(BitSet set);

    /**
     * Returns true if at least one index in the support of the distribution is in the set.
     */
    boolean containsOneOf(BitSet set);

    /**
     * Get the support of the distribution.
     */
    Set<Integer> getSupport();

    /**
     * Returns true if the distribution is empty.
     */
    boolean isEmpty();

    /**
     * Get the size of the support of the distribution.
     */
    int size();

    /**
     * Perform matrix-vector multiplication followed by min/max over uncertainty,
     * i.e. return min/max_P { sum_j P(s,j)*vect[j] }
     * @param vect Vector to multiply by
     * @param minMax Min/max uncertainty (via isMinUnc/isMaxUnc)
     */
    double mvMultUnc(double vect[], MinMax minMax);

    /**
     * Construct a copy of this uncertain distribution,
     */
    UDistribution<Value> copy();

    /**
     * Construct a copy of this uncertain distribution,
     * mapping states (index i becomes index permut[i]).
     */
    UDistribution<Value> copy(int[] permut);
}