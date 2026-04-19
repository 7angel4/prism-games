//==============================================================================
//
//	Copyright (c) 2025-
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

import common.Interval;
import explicit.rewards.CSGRewards;
import prism.ModelType;
import prism.PrismException;

import java.util.*;

public interface ICSG<Value> extends UCSG<Value>, IMDP<Value> {
    // Accessors (for Model) - default implementations

    @Override
    public default ModelType getModelType() {
        return ModelType.ICSG;
    }

    public enum UncType {
        Adv("Adversarial"),
        Ctrl("Controllable");

        private final String fullName;

        UncType(final String fullName) {
            this.fullName = fullName;
        }

        /**
         * Get the full name, in words, of this model type.
         */
        public String fullName() {
            return fullName;
        }

        /**
         * Get the PRISM keyword for this model type.
         */
        public String keyword() {
            return this.name().toLowerCase();
        }

        /**
         * Convert this uncertainty type to a MinMax object, assuming Player 1 is maximising, and Player 2 is minimising.
         *
         * @return A MinMax object representing the uncertainty type.
         */
        public MinMax toMinMax() {
            switch (this) {
                case Adv:
                    return MinMax.minMin(false, true).setMinUnc(true);
                case Ctrl:
                    return MinMax.minMin(false, true).setMinUnc(false);
                default:
                    throw new IllegalArgumentException();
            }
        }

        /**
         * Convert this uncertainty type to a MinMax object.
         *
         * @param min If nature is minimising (true) or maximising (false) value.
         * @return A MinMax object representing the uncertainty type.
         */
        public MinMax toMinMax(boolean min) {
            switch (this) {
                case Adv:
                    return MinMax.minMin(min, !min).setMinUnc(!min);
                case Ctrl:
                    return MinMax.minMin(min, !min).setMinUnc(min);
                default:
                    throw new IllegalArgumentException();
            }
        }
    }

    public UncType getUncType();

    @Override
    CSG<Interval<Value>> getCSGModel();


    public default BitSet getConcurrentPlayers(int s) {
        return getCSGModel().getConcurrentPlayers(s);
    }

    @Override
    public default BitSet[] getIndexes() {
        return getCSGModel().getIndexes();
    }

    @Override
    public default int[] getIndexes(int s, int i) {
        return getCSGModel().getIndexes(s, i);
    }

    @Override
    public default BitSet getIndexesForPlayer(int s, int p) {
        return getCSGModel().getIndexesForPlayer(s, p);
    }

    public default int[] getIdles() {
        return getCSGModel().getIdles();
    }

    @Override
    public default int getIdleForPlayer(int p) {
        return getCSGModel().getIdleForPlayer(p);
    }

    public default Iterator<Map.Entry<Integer, Double>> getDoubleTransitionsIterator(int s, int t, double val[]) {
        return getCSGModel().getDoubleTransitionsIterator(s, t, val);
    }

    @Override
    public default Distribution<Double> getDoubleChoice(int s, int i, double val[]) {
        return Distribution.ofDouble(getDoubleTransitionsIterator(s, i, val));
    }

//    public boolean filterNEforRNE(double[][] eqVal, List<List<Map<BitSet, Double>>> strats, List<CSGRewards<Double>> csgRewards, BitSet[] coalitionIndexes, int s,
//                                  boolean min, double[][] val) throws PrismException;
//
//    public double[] findRNE(double[][] eqVal, List<List<Map<BitSet, Double>>> strats, List<CSGRewards<Double>> csgRewards, BitSet[] coalitionIndexes, int s,
//                            boolean min, double[][] val) throws PrismException;

}