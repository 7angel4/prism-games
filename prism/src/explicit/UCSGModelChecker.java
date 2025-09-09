package explicit;

import acceptance.AcceptanceRabin;
import acceptance.AcceptanceReach;
import acceptance.AcceptanceType;
import automata.DA;
import automata.DASimplifyAcceptance;
import common.Interval;
import explicit.rewards.CSGRewards;
import explicit.rewards.CSGRewardsSimple;
import parser.ast.Coalition;
import parser.ast.ExpressionTemporal;
import prism.IntegerBound;
import prism.PrismComponent;
import prism.PrismException;

import java.util.*;

import static explicit.CSGModelChecker.constructDRAForInstant;

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


	/* Nonzero-sum two-player games */

//	public ModelCheckerResult computeProbBoundedEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, List<ExpressionTemporal> exprs, BitSet[] targets,
//														   BitSet[] remain, int[] bounds, int eqType, int crit, boolean min) throws PrismException {
//		mcCSG.inheritSettings(this);
//		icsg.checkLowerBoundsArePositive();
//		return mcCSG.computeProbBoundedEquilibria(icsg.getIntervalModel(), coalitions, exprs, targets, remain, bounds, eqType, crit, min);
//	}
//
//	public ModelCheckerResult computeProbReachEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, BitSet[] targets, BitSet[] remain, int eqType, int crit, boolean min)
//			throws PrismException {
//		mcCSG.inheritSettings(this);
//		icsg.checkLowerBoundsArePositive();
//		return mcCSG.computeProbReachEquilibria(icsg.getIntervalModel(), coalitions, targets, remain, eqType, crit, min);
//	}
//
//	public ModelCheckerResult computeRewBoundedEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, List<ExpressionTemporal> exprs,
//														  int[] bounds, int eqType, int crit, boolean min) throws PrismException {
//		mcCSG.inheritSettings(this);
//		icsg.checkLowerBoundsArePositive();
//		return mcCSG.computeRewBoundedEquilibria(icsg.getIntervalModel(), coalitions, rewards, exprs, bounds, eqType, crit, min);
//	}
//
//	public ModelCheckerResult computeRewReachEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, BitSet[] targets, int eqType, int crit, boolean min)
//			throws PrismException {
//		mcCSG.inheritSettings(this);
//		icsg.checkLowerBoundsArePositive();
//		return mcCSG.computeRewReachEquilibria(icsg.getIntervalModel(), coalitions, rewards, targets, eqType, crit, min);
//	}
//
//	public ModelCheckerResult computeMixedEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, List<ExpressionTemporal> exprs,
//													 BitSet bounded, BitSet[] targets, BitSet[] remain, int[] bounds, int eqType, int crit, boolean min) throws PrismException {
//		mcCSG.inheritSettings(this);
//		icsg.checkLowerBoundsArePositive();
//		return mcCSG.computeMixedEquilibria(icsg.getIntervalModel(), coalitions, rewards, exprs, bounded, targets, remain, bounds, eqType, crit, min);
//	}


	/**
	 * Deal with two-player bounded probabilistic reachability formulae
	 * @param icsg The CSG
	 * @param coalitions A list of two coalitions
	 * @param exprs The list of objectives
	 * @param targets The list of sets of target states
	 * @param remain The list of sets of states we need to remain in (in case of until)
	 * @param bounds The list of the objectives' bounds (if applicable)
	 * @param min Whether we're minimising for the first coalition
	 * @return
	 * @throws PrismException
	 */
	public ModelCheckerResult computeProbBoundedEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, List<ExpressionTemporal> exprs, BitSet[] targets,
														   BitSet[] remain, int[] bounds, int eqType, int crit, boolean min) throws PrismException
	{
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		return csgeq.computeBoundedEquilibria(icsg, coalitions, null, exprs, targets, remain, bounds, eqType, crit, min);
	}

	/**
	 * Deal with two-player probabilistic reachability formulae
	 * @param icsg The CSG
	 * @param coalitions A list of two coalitions
	 * @param targets The list of sets of target states
	 * @param remain The list of sets of states we need to remain in (in case of until)
	 * @param min Whether we're minimising for the first coalition
	 * @return
	 * @throws PrismException
	 */
	public ModelCheckerResult computeProbReachEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, BitSet[] targets, BitSet[] remain, int eqType, int crit, boolean min)
			throws PrismException
	{
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		return csgeq.computeReachEquilibria(icsg, coalitions, null, targets, remain, eqType, crit, min);
	}

	/**
	 * Deal with two-player bounded reachability rewards
	 * @param icsg The CSG
	 * @param coalitions A list of two coalitions
	 * @param rewards The list of reward structures
	 * @param exprs The list of objectives
	 * @param bounds The list of the objectives' bounds (if applicable)
	 * @param min Whether we're minimising for the first coalition
	 * @return
	 * @throws PrismException
	 */
	public ModelCheckerResult computeRewBoundedEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, List<ExpressionTemporal> exprs,
														  int[] bounds, int eqType, int crit, boolean min) throws PrismException
	{
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		return csgeq.computeBoundedEquilibria(icsg, coalitions, rewards, exprs, null, null, bounds, eqType, crit, min);
	}

	/**
	 * Deal with two-player reachability rewards formulae
	 * @param icsg The CSG
	 * @param coalitions A list of two coalitions
	 * @param rewards The list of reward structures
	 * @param targets The list of sets of target states
	 * @param min Whether we're minimising for the first coalition
	 * @return
	 * @throws PrismException
	 */
	public ModelCheckerResult computeRewReachEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, BitSet[] targets, int eqType, int crit, boolean min)
			throws PrismException
	{
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		return csgeq.computeReachEquilibria(icsg, coalitions, rewards, targets, null, eqType, crit, min);
	}

	/**
	 * Deal with computing mixed bounded and unbounded equilibria.
	 * @param icsg The CSG
	 * @param coalitions The list of coalitions
	 * @param rewards The list of reward structures
	 * @param exprs The list of objectives
	 * @param bounded Index of the objectives which are bounded
	 * @param targets The list of sets of target states
	 * @param remain The list of sets of states we need to remain in (in case of until)
	 * @param bounds The list of the objectives' bounds (if applicable)
	 * @param min Whether we're minimising for the first coalition
	 * @return
	 * @throws PrismException
	 */
	public ModelCheckerResult computeMixedEquilibria(ICSG<Double> icsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, List<ExpressionTemporal> exprs,
															 BitSet bounded, BitSet[] targets, BitSet[] remain, int[] bounds, int eqType, int crit, boolean min) throws PrismException
	{
		mcCSG.inheritSettings(this);
		icsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		LTLModelChecker ltlmc = new LTLModelChecker(this);
		LTLModelChecker.LTLProduct<ICSG<Double>> product;
		List<CSGRewards<Double>> newrewards = new ArrayList<>();
		BitSet newremain[];
		BitSet newtargets[];
		int index, i, s, t;
		boolean rew;

		/*
		Path currentRelativePath = Paths.get("");
		String path = currentRelativePath.toAbsolutePath().toString();
		*/

		rew = rewards != null;
		index = bounded.nextSetBit(0);

		newremain = new BitSet[remain.length];
		newtargets = new BitSet[targets.length];

		Arrays.fill(newremain, null);

		/*
		LTL2DA ltl2da = new LTL2DA(this);
		DA<BitSet,? extends AcceptanceOmega> daex = ltl2da.convertLTLFormulaToDA(exprs.get(index), icsg.getConstantValues(), AcceptanceType.RABIN);
		try(OutputStream out1 =
				new FileOutputStream(path + "/dra.dot")) {
					try (PrintStream printStream =
							new PrintStream(out1)) {
								da.printDot(printStream);
								printStream.close();
					}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		*/

		AcceptanceType[] allowedAcceptance = { AcceptanceType.RABIN, AcceptanceType.REACH, AcceptanceType.BUCHI, AcceptanceType.STREETT,
				AcceptanceType.GENERIC };

		//icsg.exportToDotFile(path + "/model.dot");

		if (rew) {
			BitSet all = new BitSet();
			all.set(0, icsg.getNumStates());
			DA<BitSet, AcceptanceRabin> da = null;
			Vector<BitSet> labelBS = new Vector<BitSet>();
			labelBS.add(0, all);
			targets[index] = all;
			switch (exprs.get(index).getOperator()) {
				case ExpressionTemporal.R_I:
					da = constructDRAForInstant("L0", new IntegerBound(null, false, bounds[index] + 1, false));
					break;
				case ExpressionTemporal.R_C:
					da = constructDRAForInstant("L0", new IntegerBound(null, false, bounds[index], false));
					break;
			}

			DASimplifyAcceptance.simplifyAcceptance(this, da, AcceptanceType.REACH);

			/*
			try(OutputStream out1 =
					new FileOutputStream(path + "/dra.dot")) {
						try (PrintStream printStream =
								new PrintStream(out1)) {
									da.printDot(printStream);
									printStream.close();
						}
			}
			catch(Exception e) {
				e.printStackTrace();
			}
			*/

			mainLog.println("\nConstructing CSG-" + da.getAutomataType() + " product...");
			product = ltlmc.constructProductModel(da, icsg, labelBS, null);
			mainLog.print("\n" + product.getProductModel().infoStringTable());
		} else {
			product = ltlmc.constructProductICSG(this, icsg, exprs.get(index), null, allowedAcceptance);
		}

		((IntervalModelExplicit<Double>) product.productModel).clearInitialStates();
		((IntervalModelExplicit<Double>) product.productModel).addInitialState(product.getModelState(icsg.getFirstInitialState()));

		//product.productModel.exportToDotFile(path + "/product.dot");

		/*
		try {
			PrismFileLog pflog = new PrismFileLog(path + "/product.dot");
			System.out.println(path + "/product.dot");
			product.productModel.exportToDotFile(pflog, null, true);
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		*/

		if (product.getAcceptance() instanceof AcceptanceReach) {
			mainLog.println("\nSkipping BSCC computation since acceptance is defined via goal states...");
			newtargets[index] = ((AcceptanceReach) product.getAcceptance()).getGoalStates();
		} else {
			mainLog.println("\nFinding accepting BSCCs...");
			newtargets[index] = ltlmc.findAcceptingBSCCs(product.getProductModel(), product.getAcceptance());
		}

		for (i = 0; i < targets.length; i++) {
			if (i != index)
				newtargets[i] = product.liftFromModel(targets[i]);
		}

		for (i = 0; i < remain.length; i++) {
			if (remain[i] != null) {
				newremain[i] = product.liftFromModel(remain[i]);
			}
		}

		if (rew) {
			for (i = 0; i < coalitions.size(); i++) {
				CSGRewards<Double> reward = new CSGRewardsSimple<>(product.productModel.getNumStates());
				if (i != index) {
					for (s = 0; s < product.productModel.getNumStates(); s++) {
						((CSGRewardsSimple<Double>) reward).addToStateReward(s, rewards.get(i).getStateReward(product.getModelState(s)));
						for (t = 0; t < product.productModel.getNumChoices(s); t++) {
							((CSGRewardsSimple<Double>) reward).addToTransitionReward(s, t, rewards.get(i).getTransitionReward(product.getModelState(s), t));
						}
					}
				} else {
					switch (exprs.get(index).getOperator()) {
						case ExpressionTemporal.R_I:
							for (s = 0; s < product.productModel.getNumStates(); s++) {
								for (t = 0; t < product.productModel.getNumChoices(s); t++) {
									for (Iterator<Integer> iter = product.productModel.getSuccessorsIterator(s, t); iter.hasNext();) {
										int u = iter.next();
										if (newtargets[index].get(u)) {
											((CSGRewardsSimple<Double>) reward).addToStateReward(s, rewards.get(i).getStateReward(product.getModelState(s)));
										}
									}
									((CSGRewardsSimple<Double>) reward).addToTransitionReward(s, t, 0.0);
								}
							}
							break;
						case ExpressionTemporal.R_C:
							for (s = 0; s < product.productModel.getNumStates(); s++) {
								if (!newtargets[index].get(s)) {
									((CSGRewardsSimple<Double>) reward).addToStateReward(s, rewards.get(i).getStateReward(product.getModelState(s)));
									for (t = 0; t < product.productModel.getNumChoices(s); t++) {
										((CSGRewardsSimple<Double>) reward).addToTransitionReward(s, t, rewards.get(i).getTransitionReward(product.getModelState(s), t));
									}
								} else {
									((CSGRewardsSimple<Double>) reward).addToStateReward(s, 0.0);
									for (t = 0; t < product.productModel.getNumChoices(s); t++) {
										((CSGRewardsSimple<Double>) reward).addToTransitionReward(s, t, 0.0);
									}
								}
							}
							break;
					}
				}
				newrewards.add(i, reward);
			}
			/*** Optional filtering ***/
			/*
			CSG csg_rm = new CSG(icsg.getPlayers());
			List<CSGRewards> csg_rew_rm = new ArrayList<CSGRewards>();
			map_state = new HashMap<Integer, Integer>();
			list_state = new ArrayList<State>();
			map_state.put(product.productModel.getFirstInitialState(), csg_rm.addState());
			csg_rm.addInitialState(map_state.get(product.productModel.getFirstInitialState()));
			for (i = 0; i < rewards.size(); i++) {
				csg_rew_rm.add(i, new CSGRewardsSimple(product.productModel.getNumStates()));
			}
			filterStates(product.productModel, csg_rm, newrewards, csg_rew_rm, product.productModel.getFirstInitialState());
			csg_rm.setVarList(icsg.getVarList());
			csg_rm.setStatesList(list_state);
			csg_rm.setActions(icsg.getActions());
			csg_rm.setPlayers(icsg.getPlayers());
			csg_rm.setIndexes(icsg.getIndexes());
			csg_rm.setIdles(icsg.getIdles());
			csg_rm.exportToDotFile(path + "/filtered.dot");
			BitSet[] filtered_targets = new BitSet[targets.length];
			for (i = 0; i < targets.length; i++) {
				filtered_targets[i] = new BitSet();
				for (s = newtargets[i].nextSetBit(0); s >= 0; s = newtargets[i].nextSetBit(s + 1)) {
					if (map_state.get(s) != null)
						filtered_targets[i].set(map_state.get(s));
					System.out.println(map_state.get(s));
				}
			}
			res = csgeq.computeReachEquilibria(csg_rm, coalitions, csg_rew_rm, filtered_targets, null);
			*/
			return csgeq.computeReachEquilibria(product.productModel, coalitions, newrewards, newtargets, null, eqType, crit, min);
		} else {
			return csgeq.computeReachEquilibria(product.productModel, coalitions, null, newtargets, newremain, eqType, crit, min);
		}
	}

}
