package explicit;

import java.util.*;

import prism.*;

import acceptance.AcceptanceRabin;
import acceptance.AcceptanceReach;
import acceptance.AcceptanceType;
import automata.DA;
import automata.DASimplifyAcceptance;
import explicit.rewards.CSGRewards;
import explicit.rewards.CSGRewardsSimple;
import parser.ast.Coalition;
import parser.ast.ExpressionTemporal;

import static explicit.CSGModelChecker.constructDRAForInstant;

public class UCSGModelChecker extends ProbModelChecker
{
	// CSGModelChecker in order to use the CSG LP/matrix-game machinery
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

	private void prepare(UCSG<Double> ucsg, BitSet target) throws PrismException
	{
		mcCSG.inheritSettings(this);
		ucsg.checkLowerBoundsArePositive();
		ucsg.checkForDeadlocks(target);
	}

	/* =========================
	 * Reachability / next-step
	 * ========================= */

	public ModelCheckerResult computeReachProbs(UCSG<Double> ucsg, BitSet target, MinMax minMax, int bound, Coalition coalition) throws PrismException
	{
		prepare(ucsg, target);
		return mcCSG.computeReachProbs(ucsg.getCSGModel(), target, minMax.isMin1(), minMax.isMin2(), bound, coalition);
	}

	public ModelCheckerResult computeNextProbs(UCSG<Double> ucsg, BitSet target, MinMax minMax) throws PrismException
	{
		prepare(ucsg, target);
		return mcCSG.computeNextProbs(ucsg.getCSGModel(), target, minMax.isMin1(), minMax.isMin2(), minMax.getCoalition());
	}

	public ModelCheckerResult computeUntilProbs(UCSG<Double> ucsg, BitSet remain, BitSet target, int bound, MinMax minmax) throws PrismException
	{
		prepare(ucsg, target);
		return mcCSG.computeUntilProbs(ucsg.getCSGModel(), remain, target, bound, minmax.isMin1(), minmax.isMin2(), minmax.getCoalition());
	}

	public ModelCheckerResult computeUntilProbs(UCSG<Double> ucsg, BitSet remain, BitSet target, MinMax minmax) throws PrismException
	{
		return computeUntilProbs(ucsg, remain, target, maxIters, minmax);
	}

	public ModelCheckerResult computeBoundedUntilProbs(UCSG<Double> ucsg, BitSet remain, BitSet target, int k, MinMax minmax) throws PrismException
	{
		return computeUntilProbs(ucsg, remain, target, k, minmax);
	}

	/* =========================
	 * Reachability rewards
	 * ========================= */

	public ModelCheckerResult computeReachRewardsCumulative(UCSG<Double> ucsg, CSGRewards<Double> rewards, BitSet target, MinMax minMax) throws PrismException
	{
		prepare(ucsg, target);
		return mcCSG.computeReachRewardsCumulative(ucsg.getCSGModel(), minMax.getCoalition(), rewards, target, minMax.isMin1(), minMax.isMin2(), false);
	}

	public ModelCheckerResult computeReachRewardsInfinity(UCSG<Double> ucsg, CSGRewards<Double> rewards, BitSet target, MinMax minMax) throws PrismException
	{
		prepare(ucsg, target);
		return mcCSG.computeReachRewardsInfinity(ucsg.getCSGModel(), minMax.getCoalition(), rewards, target, minMax.isMin1(), minMax.isMin2());
	}

	public ModelCheckerResult computeReachRewards(UCSG<Double> ucsg, CSGRewards<Double> rewards, BitSet target, int unreachingSemantics, MinMax minMax) throws PrismException
	{
		switch (unreachingSemantics) {
			case CSGModelChecker.R_INFINITY:
				return computeReachRewardsInfinity(ucsg, rewards, target, minMax);
			case CSGModelChecker.R_CUMULATIVE:
				return computeReachRewardsCumulative(ucsg, rewards, target, minMax);
			case CSGModelChecker.R_ZERO:
				throw new PrismException("F0 is not yet supported for CSGs.");
			default:
				throw new PrismException("Unknown semantics for runs unreaching the target in CSGModelChecker: " + unreachingSemantics);
		}
	}

	public ModelCheckerResult computeCumulativeRewards(UCSG<Double> ucsg, CSGRewards<Double> rewards, int k, MinMax minMax) throws PrismException
	{
		prepare(ucsg, new BitSet());
		return mcCSG.computeCumulativeRewards(ucsg.getCSGModel(), rewards, minMax.getCoalition(), k, minMax.isMin1(), minMax.isMin2(), false);
	}

	public ModelCheckerResult computeTotalRewards(UCSG<Double> ucsg, CSGRewards<Double> rewards, MinMax minMax) throws PrismException
	{
		prepare(ucsg, new BitSet());
		return mcCSG.computeTotalRewards(ucsg.getCSGModel(), rewards, minMax.isMin1(), minMax.isMin2(), minMax.getCoalition());
	}

	/* =========================
	 * Nonzero-sum two-player games
	 * ========================= */

	public ModelCheckerResult computeProbBoundedEquilibria(UCSG<Double> ucsg, List<Coalition> coalitions, List<ExpressionTemporal> exprs, BitSet[] targets,
														   BitSet[] remain, int[] bounds, int eqType, int crit, boolean min) throws PrismException
	{
		mcCSG.inheritSettings(this);
		ucsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		return csgeq.computeBoundedEquilibria(ucsg, coalitions, null, exprs, targets, remain, bounds, eqType, crit, min);
	}

	/**
	 * Deal with two-player probabilistic reachability formulae
	 * @param ucsg The CSG
	 * @param coalitions A list of two coalitions
	 * @param targets The list of sets of target states
	 * @param remain The list of sets of states we need to remain in (in case of until)
	 * @param min Whether we're minimising for the first coalition
	 * @return
	 * @throws PrismException
	 */
	public ModelCheckerResult computeProbReachEquilibria(UCSG<Double> ucsg, List<Coalition> coalitions, BitSet[] targets, BitSet[] remain, int eqType, int crit, boolean min)
			throws PrismException
	{
		mcCSG.inheritSettings(this);
		ucsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		return csgeq.computeReachEquilibria(ucsg, coalitions, null, targets, remain, eqType, crit, min);
	}


	/**
	 * Deal with two-player reachability rewards formulae
	 * @param ucsg The CSG
	 * @param coalitions A list of two coalitions
	 * @param rewards The list of reward structures
	 * @param targets The list of sets of target states
	 * @param min Whether we're minimising for the first coalition
	 * @return
	 * @throws PrismException
	 */
	public ModelCheckerResult computeRewReachEquilibria(UCSG<Double> ucsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, BitSet[] targets, int eqType, int crit, boolean min)
			throws PrismException
	{
		mcCSG.inheritSettings(this);
		ucsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		csgeq.mdpmc.inheritSettings(this.mcCSG);
		return csgeq.computeReachEquilibria(ucsg, coalitions, rewards, targets, null, eqType, crit, min);
	}

	public ModelCheckerResult computeRewBoundedEquilibria(UCSG<Double> ucsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, List<ExpressionTemporal> exprs,
														  int[] bounds, int eqType, int crit, boolean min) throws PrismException
	{
		mcCSG.inheritSettings(this);
		ucsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		return csgeq.computeBoundedEquilibria(ucsg, coalitions, rewards, exprs, null, null, bounds, eqType, crit, min);
	}
	
	

	/**
	 * Deal with computing mixed bounded and unbounded equilibria.
	 * @param ucsg The CSG
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
	public ModelCheckerResult computeMixedEquilibria(UCSG<Double> ucsg, List<Coalition> coalitions, List<CSGRewards<Double>> rewards, List<ExpressionTemporal> exprs,
													 BitSet bounded, BitSet[] targets, BitSet[] remain, int[] bounds, int eqType, int crit, boolean min) throws PrismException
	{
		mcCSG.inheritSettings(this);
		ucsg.checkLowerBoundsArePositive();
		UCSGModelCheckerEquilibria csgeq = new UCSGModelCheckerEquilibria(this.mcCSG);
		csgeq.inheritSettings(this.mcCSG);
		LTLModelChecker ltlmc = new LTLModelChecker(this);
		LTLModelChecker.LTLProduct<UCSG<Double>> product;
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
		DA<BitSet,? extends AcceptanceOmega> daex = ltl2da.convertLTLFormulaToDA(exprs.get(index), ucsg.getConstantValues(), AcceptanceType.RABIN);
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

		//ucsg.exportToDotFile(path + "/model.dot");

		if (rew) {
			BitSet all = new BitSet();
			all.set(0, ucsg.getNumStates());
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
			product = ltlmc.constructProductModel(da, ucsg, labelBS, null);
			mainLog.print("\n" + product.getProductModel().infoStringTable());
		} else {
			product = ltlmc.constructProductUCSG(this, ucsg, exprs.get(index), null, allowedAcceptance);
		}

		((ModelExplicit<Double>) product.productModel).clearInitialStates();
		((ModelExplicit<Double>) product.productModel).addInitialState(product.getModelState(ucsg.getFirstInitialState()));

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
			CSG csg_rm = new CSG(ucsg.getPlayers());
			List<CSGRewards> csg_rew_rm = new ArrayList<CSGRewards>();
			map_state = new HashMap<Integer, Integer>();
			list_state = new ArrayList<State>();
			map_state.put(product.productModel.getFirstInitialState(), csg_rm.addState());
			csg_rm.addInitialState(map_state.get(product.productModel.getFirstInitialState()));
			for (i = 0; i < rewards.size(); i++) {
				csg_rew_rm.add(i, new CSGRewardsSimple(product.productModel.getNumStates()));
			}
			filterStates(product.productModel, csg_rm, newrewards, csg_rew_rm, product.productModel.getFirstInitialState());
			csg_rm.setVarList(ucsg.getVarList());
			csg_rm.setStatesList(list_state);
			csg_rm.setActions(ucsg.getActions());
			csg_rm.setPlayers(ucsg.getPlayers());
			csg_rm.setIndexes(ucsg.getIndexes());
			csg_rm.setIdles(ucsg.getIdles());
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



	public static void main(String[] args) throws Exception {
		PrismLog log = new PrismPrintStreamLog(System.out);
		Prism prism = new Prism(log);

		// Build a tiny 2-player L1CSG with 3 states:
		// 0 -> {1,2} with nominal probs 0.3, 0.7 and L1 radius 0.2
		// 1 -> self-loop
		// 2 -> self-loop (target)
		L1CSGSimple<Double> g = new L1CSGSimple<>();
		g.addStates(3);
		g.addInitialState(0);

		g.setPlayerNames(Arrays.asList("P1", "P2"));

		// Set global action labels, then idle actions
		g.setActions(new ArrayList<>(Arrays.asList("a1", "a2")));
		g.addIdleIndexes();

		// Declare each player's available action set BEFORE adding choices
		BitSet[] idx = new BitSet[2];
		idx[0] = new BitSet();
		idx[1] = new BitSet();
		idx[0].set(1);
		idx[0].set(g.getIdleForPlayer(0));
		idx[1].set(g.getIdleForPlayer(1));
		g.setIndexes(idx);
		g.setIdles(new int[]{3, 4});

		// add the choices
		int p1Act = 1;
		int p2Idle = g.getIdleForPlayer(1);

		Distribution<Double> d0 = new Distribution<>(Evaluator.forDouble());
		d0.add(1, 0.3);
		d0.add(2, 0.7);
		g.addActionLabelledChoice(0, d0, 0.2, new int[]{p1Act, p2Idle});

		Distribution<Double> d1 = new Distribution<>(Evaluator.forDouble());
		d1.add(1, 1.0);
		g.addActionLabelledChoice(1, d1, 0.2, new int[]{p1Act, p2Idle});

		Distribution<Double> d2 = new Distribution<>(Evaluator.forDouble());
		d2.add(2, 1.0);
		g.addActionLabelledChoice(2, d2, 0.2, new int[]{p1Act, p2Idle});

		g.findDeadlocks(true);

		System.out.println("Model type: " + g.getModelType());
		System.out.println(g);

		UCSGModelChecker mc = new UCSGModelChecker(prism);

		BitSet target = new BitSet();
		target.set(2);

		Coalition coalition = new Coalition(Arrays.asList("P1"));
//		Coalition coalition = new Coalition();
//		coalition.setAllPlayers();

		MinMax minUnc = MinMax.minMin(false, true).setMinUnc(true);
		MinMax maxUnc = MinMax.minMin(false, true).setMinUnc(false);

		g.setMinUncertainty(minUnc.isMinUnc());
		ModelCheckerResult r1 = mc.computeReachProbs(g, target, minUnc, mc.maxIters, coalition);

		g.setMinUncertainty(maxUnc.isMinUnc());
		ModelCheckerResult r2 = mc.computeReachProbs(g, target, maxUnc, mc.maxIters, coalition);

		System.out.println("Robust reach (min uncertainty): " + r1.soln[g.getFirstInitialState()]);
		System.out.println("Optimistic reach (max uncertainty): " + r2.soln[g.getFirstInitialState()]);
	}
}