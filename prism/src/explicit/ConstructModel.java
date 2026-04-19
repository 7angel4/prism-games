//==============================================================================
//
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford)
//	* Vojtech Forejt <vojtech.forejt@cs.ox.ac.uk> (University of Oxford)
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

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;

import common.Interval;
import parser.State;
import parser.Values;
import parser.VarList;
import prism.ModelGenerator;
import prism.ModelType;
import prism.PlayerInfoOwner;
import prism.Prism;
import prism.PrismComponent;
import prism.PrismException;
import prism.PrismLog;
import prism.PrismNotSupportedException;
import prism.PrismPrintStreamLog;
import prism.ProgressDisplay;
import prism.UndefinedConstants;

/**
 * Class to perform explicit-state reachability and model construction.
 * The information about the model to be built is provided via a {@link prism.ModelGenerator} interface.
 * To build a PRISM model, use {@link simulator.ModulesFileModelGenerator}.
 */
public class ConstructModel extends PrismComponent
{
	/** Find deadlocks during model construction? */
	protected boolean findDeadlocks = true;
	/** Automatically fix deadlocks? */
	protected boolean fixDeadlocks = true;
	/** Sort the reachable states before constructing the model? */
	protected boolean sortStates = true;
	/** Build a sparse representation, if possible? */
	protected boolean buildSparse = true;
	/** Should actions be attached to distributions (and used to distinguish them)? */
	protected boolean distinguishActions = true;
	/** Should labels be processed and attached to the model? */
	protected boolean attachLabels = true;

	/** How to resolve uncertain composition. */
	public enum CompositionType {
		SMART,
		L1
	}

	protected CompositionType compositionType = CompositionType.SMART;

	/** Optional hook for L1 radii. Implement this in the concrete model generator if needed. */
	public interface L1RadiusProvider {
		double getChoiceL1Radius(int stateIndex, int choiceIndex) throws PrismException;
	}

	/** Reachable states */
	protected List<State> statesList;

	public ConstructModel(PrismComponent parent) throws PrismException
	{
		super(parent);
	}

	public List<State> getStatesList()
	{
		return statesList;
	}

	public void setCompositionType(CompositionType type)
	{
		this.compositionType = type;
	}

	public void setFixDeadlocks(boolean fixDeadlocks)
	{
		this.fixDeadlocks = fixDeadlocks;
	}

	public void setSortStates(boolean sortStates)
	{
		this.sortStates = sortStates;
	}

	public void setBuildSparse(boolean buildSparse)
	{
		this.buildSparse = buildSparse;
	}

	public void setDistinguishActions(boolean distinguishActions)
	{
		this.distinguishActions = distinguishActions;
	}

	public void setAttachLabels(boolean attachLabels)
	{
		this.attachLabels = attachLabels;
	}

	public List<State> computeReachableStates(ModelGenerator<?> modelGen) throws PrismException
	{
		constructModel(modelGen, true);
		return getStatesList();
	}

	public <Value> Model<Value> constructModel(ModelGenerator<Value> modelGen) throws PrismException
	{
		return constructModel(modelGen, false);
	}

	@SuppressWarnings("unchecked")
	public <Value> Model<Value> constructModel(ModelGenerator<Value> modelGen, boolean justReach) throws PrismException
	{
		ModelType modelType;
		StateStorage<State> states;
		LinkedList<State> explore;
		State state, stateNew;

		ModelSimple<?> modelSimple = null;
		DTMCSimple<Value> dtmc = null;
		CTMCSimple<Value> ctmc = null;
		MDPSimple<Value> mdp = null;
		POMDPSimple<Value> pomdp = null;
		CTMDPSimple<Value> ctmdp = null;
		STPGSimple<Value> stpg = null;
		CSGSimple<Value> csg = null;
		SMGSimple<Value> smg = null;
		IDTMCSimple<Value> idtmc = null;
		IMDPSimple<Value> imdp = null;
		IPOMDPSimple<Value> ipomdp = null;
		ICSGSimple<Value> icsg = null;
		L1MDPSimple<Value> l1mdp = null;
		L1CSGSimple<Value> l1csg = null;
		LTSSimple<Value> lts = null;

		Distribution<Value> distr = null;
		Distribution<Interval<Value>> distrUnc = null;

		List<String> playerNames = null;
		int i, j, nc, nt, src, dest, player;
		long timer;

		modelType = modelGen.getModelType();

		VarList varList = modelGen.createVarList();
		if (modelGen.containsUnboundedVariables()) {
			mainLog.printWarning("Model contains one or more unbounded variables: model construction may not terminate");
		}

		mainLog.print("\nComputing reachable states...");
		mainLog.flush();
		ProgressDisplay progress = new ProgressDisplay(mainLog);
		progress.start();
		timer = System.currentTimeMillis();

		if (modelType.multiplePlayers()) {
			playerNames = new ArrayList<>();
			for (i = 0; i < modelGen.getNumPlayers(); i++) {
				String name = modelGen.getPlayerName(i);
				if (!("".equals(name)) && playerNames.contains(name)) {
					throw new PrismException("Duplicate player name \"" + name + "\"");
				}
				playerNames.add(name);
			}
			if (modelType == ModelType.STPG && playerNames.size() != 2) {
				throw new PrismException("An STPG should define exactly 2 players");
			}
		}

		if (modelType.concurrent() && modelGen.getActions() == null) {
			throw new PrismException("Model generator must implement getActions() for concurrent games");
		}

		if (!justReach) {
			switch (modelType) {
				case DTMC:
					modelSimple = dtmc = new DTMCSimple<>();
					break;
				case CTMC:
					modelSimple = ctmc = new CTMCSimple<>();
					break;
				case CSG:
					modelSimple = csg = new CSGSimple<>();
					break;
				case MDP:
					modelSimple = mdp = new MDPSimple<>();
					break;
				case POMDP:
					modelSimple = pomdp = new POMDPSimple<>();
					break;
				case CTMDP:
					modelSimple = ctmdp = new CTMDPSimple<>();
					break;
				case IDTMC:
					modelSimple = idtmc = new IDTMCSimple<>();
					break;
				case IMDP:
					modelSimple = imdp = new IMDPSimple<>();
					break;
				case IPOMDP:
					modelSimple = ipomdp = new IPOMDPSimple<>();
					break;
				case ICSG:
					modelSimple = icsg = new ICSGSimple<>();
					break;
				case L1MDP:
					modelSimple = l1mdp = new L1MDPSimple<>();
					break;
				case L1CSG:
					modelSimple = l1csg = new L1CSGSimple<>();
					break;
				case LTS:
					modelSimple = lts = new LTSSimple<>();
					break;
				case STPG:
					modelSimple = stpg = new STPGSimple<>();
					break;
				case SMG:
					modelSimple = smg = new SMGSimple<>();
					break;
				case PTA:
				case POPTA:
					throw new PrismNotSupportedException("Model construction not supported for " + modelType + "s");
			}

			if (modelSimple instanceof PlayerInfoOwner) {
				((PlayerInfoOwner) modelSimple).setPlayerNames(playerNames);
			}

			((ModelExplicit<Value>) modelSimple).setEvaluator(modelGen.getEvaluator());
			if (modelSimple instanceof IntervalModelExplicit) {
				((IntervalModelExplicit<Value>) modelSimple).setIntervalEvaluator(modelGen.getIntervalEvaluator());
			}
			((ModelExplicit<Value>) modelSimple).setVarList(varList);

			List<Object> actions = modelGen.getActions();
			if (actions != null) {
				((ModelExplicit<Value>) modelSimple).setActions(actions);
			}
		}

		states = new IndexedSet<State>(true);
		explore = new LinkedList<State>();

		for (State initState : modelGen.getInitialStates()) {
			explore.add(initState);
			states.add(initState);
			if (!justReach) {
				modelSimple.addState();
				modelSimple.addInitialState(modelSimple.getNumStates() - 1);
			}
		}

		src = -1;
		while (!explore.isEmpty()) {
			state = explore.removeFirst();
			src++;

			modelGen.exploreState(state);
			nc = modelGen.getNumChoices();

			if (modelType.multiplePlayers() && !modelType.concurrent()) {
				player = modelGen.getPlayerOwningState();
				if (modelType == ModelType.STPG) {
					stpg.setPlayer(src, player);
				} else if (modelType == ModelType.SMG) {
					smg.setPlayer(src, player);
				}
			}

			for (i = 0; i < nc; i++) {
				if (modelType.partiallyObservable()) {
					if (((NondetModel<Value>) modelSimple).getChoiceByAction(src, modelGen.getChoiceAction(i)) != -1) {
						String act = modelGen.getChoiceAction(i) == null ? "" : modelGen.getChoiceAction(i).toString();
						String err = modelType + " is not allowed duplicate action";
						err += " (\"" + act + "\") in state " + state.toString(modelGen);
						throw new PrismException(err);
					}
				}

				if (!justReach && modelType.nondeterministic()) {
					if (modelType == ModelType.IMDP || modelType == ModelType.IPOMDP || modelType == ModelType.ICSG) {
						distrUnc = new Distribution<>(modelGen.getIntervalEvaluator());
					} else {
						distr = new Distribution<>(modelGen.getEvaluator());
					}
				}

				nt = modelGen.getNumTransitions(i);
				for (j = 0; j < nt; j++) {
					stateNew = modelGen.computeTransitionTarget(i, j);

					if (states.add(stateNew)) {
						explore.add(stateNew);
						if (!justReach) {
							modelSimple.addState();
						}
					}

					dest = states.getIndexOfLastAdd();

					Object action = null;
					if (distinguishActions && !modelType.nondeterministic()) {
						action = modelGen.getTransitionAction(i, j);
					}

					if (!justReach) {
						switch (modelType) {
							case DTMC:
								dtmc.addToProbability(src, dest, modelGen.getTransitionProbability(i, j), action);
								break;
							case CTMC:
								ctmc.addToProbability(src, dest, modelGen.getTransitionProbability(i, j), action);
								break;
							case IDTMC:
								idtmc.addToProbability(src, dest, modelGen.getTransitionProbabilityInterval(i, j), action);
								break;
							case MDP:
							case POMDP:
							case CTMDP:
							case STPG:
							case SMG:
							case CSG:
							case L1MDP:
							case L1CSG:
								distr.add(dest, modelGen.getTransitionProbability(i, j));
								break;
							case IMDP:
							case IPOMDP:
							case ICSG:
								distrUnc.add(dest, modelGen.getTransitionProbabilityInterval(i, j));
								break;
							case LTS:
								if (distinguishActions) {
									lts.addActionLabelledTransition(src, dest, modelGen.getChoiceAction(i));
								} else {
									lts.addTransition(src, dest);
								}
								break;
							case PTA:
							case POPTA:
								throw new PrismNotSupportedException("Model construction not supported for " + modelType + "s");
						}
					}
				}

				int ch = -1;
				if (!justReach) {
					if (modelType == ModelType.MDP) {
						if (distinguishActions) {
							mdp.addActionLabelledChoice(src, distr, modelGen.getChoiceAction(i));
						} else {
							mdp.addChoice(src, distr);
						}
					} else if (modelType == ModelType.L1MDP) {
						double radius = 0.0;
						if (modelGen instanceof L1RadiusProvider) {
							radius = ((L1RadiusProvider) modelGen).getChoiceL1Radius(src, i);
						}
						if (distinguishActions) {
							l1mdp.addActionLabelledChoice(src, distr, radius, modelGen.getChoiceAction(i));
						} else {
							l1mdp.addActionLabelledChoice(src, distr, radius, null);
						}
					} else if (modelType == ModelType.POMDP) {
						if (distinguishActions) {
							pomdp.addActionLabelledChoice(src, distr, modelGen.getChoiceAction(i));
						} else {
							pomdp.addChoice(src, distr);
						}
					} else if (modelType == ModelType.CTMDP) {
						if (distinguishActions) {
							ctmdp.addActionLabelledChoice(src, distr, modelGen.getChoiceAction(i));
						} else {
							ctmdp.addChoice(src, distr);
						}
					} else if (modelType == ModelType.STPG) {
						if (distinguishActions) {
							stpg.addActionLabelledChoice(src, distr, modelGen.getTransitionAction(i, 0));
						} else {
							stpg.addChoice(src, distr);
						}
					} else if (modelType == ModelType.CSG) {
						csg.addActionLabelledChoice(src, distr, modelGen.getTransitionIndexes(i));
					} else if (modelType == ModelType.L1CSG) {
						double radius = 0.0;
						if (modelGen instanceof L1RadiusProvider) {
							radius = ((L1RadiusProvider) modelGen).getChoiceL1Radius(src, i);
						}
						l1csg.addActionLabelledChoice(src, distr, radius, modelGen.getTransitionIndexes(i));
					} else if (modelType == ModelType.SMG) {
						if (distinguishActions) {
							smg.addActionLabelledChoice(src, distr, modelGen.getTransitionAction(i, 0));
						} else {
							smg.addChoice(src, distr);
						}
					} else if (modelType == ModelType.IMDP) {
						if (distinguishActions) {
							ch = imdp.addActionLabelledChoice(src, distrUnc, modelGen.getChoiceAction(i));
						} else {
							ch = imdp.addChoice(src, distrUnc);
						}
					} else if (modelType == ModelType.IPOMDP) {
						if (distinguishActions) {
							ch = ipomdp.addActionLabelledChoice(src, distrUnc, modelGen.getChoiceAction(i));
						} else {
							ch = ipomdp.addChoice(src, distrUnc);
						}
					} else if (modelType == ModelType.ICSG) {
						ch = icsg.addActionLabelledChoice(src, distrUnc, modelGen.getTransitionIndexes(i));
					}
				}

				if (modelType == ModelType.IDTMC) {
					idtmc.delimit(src);
				} else if (modelType == ModelType.IMDP) {
					imdp.delimit(src, ch);
				} else if (modelType == ModelType.IPOMDP) {
					ipomdp.delimit(src, ch);
				} else if (modelType == ModelType.ICSG) {
					icsg.delimit(src, ch);
				}
			}

			if (!justReach && (modelType == ModelType.POMDP || modelType == ModelType.IPOMDP)) {
				setStateObservation(modelGen, (PartiallyObservableModel<Value>) modelSimple, src, state);
			}

			progress.updateIfReady(src + 1);
		}

		progress.update(src + 1);
		progress.end(" states");

		mainLog.print("Reachable states exploration" + (justReach ? "" : " and model construction"));
		mainLog.println(" done in " + ((System.currentTimeMillis() - timer) / 1000.0) + " secs.");

		if (modelType == ModelType.CSG) {
			csg.addIdleIndexes();
		} else if (modelType == ModelType.ICSG) {
			icsg.addIdleIndexes();
		} else if (modelType == ModelType.L1CSG) {
			l1csg.addIdleIndexes();
		}

		if (!justReach && findDeadlocks) {
			if (modelType != ModelType.CSG && modelType != ModelType.ICSG && modelType != ModelType.L1CSG) {
				modelSimple.findDeadlocks(fixDeadlocks);
			} else {
				modelSimple.findDeadlocks(false);
				if (modelSimple.getNumDeadlockStates() > 0) {
					if (modelType == ModelType.CSG) {
						for (Integer s : modelSimple.getDeadlockStates()) {
							csg.fixDeadlock(s);
						}
					} else if (modelType == ModelType.ICSG) {
						for (Integer s : modelSimple.getDeadlockStates()) {
							icsg.fixDeadlock(s);
						}
					} else { // modelType == ModelType.L1CSG
						for (Integer s : modelSimple.getDeadlockStates()) {
							l1csg.fixDeadlock(s);
						}
					}
				}
			}
		}

		int[] permut = null;

		if (sortStates) {
			mainLog.println("Sorting reachable states list...");
			permut = states.buildSortingPermutation();
			statesList = states.toPermutedArrayList(permut);
		} else {
			statesList = states.toArrayList();
		}
		states.clear();
		states = null;

		ModelExplicit<Value> model = null;
		if (!justReach) {
			boolean isDbl = modelSimple.getEvaluator().one() instanceof Double;
			switch (modelType) {
				case DTMC:
					if (buildSparse && isDbl) {
						model = (ModelExplicit<Value>) (sortStates ? new DTMCSparse((DTMCSimple<Double>) dtmc, permut) : new DTMCSparse((DTMCSimple<Double>) dtmc));
					} else {
						model = sortStates ? new DTMCSimple<>(dtmc, permut) : (DTMCSimple<Value>) dtmc;
					}
					break;
				case CTMC:
					model = sortStates ? new CTMCSimple<>(ctmc, permut) : (CTMCSimple<Value>) ctmc;
					break;
				case MDP:
					if (buildSparse && isDbl) {
						model = (ModelExplicit<Value>) (sortStates ? new MDPSparse((MDPSimple<Double>) mdp, true, permut) : new MDPSparse((MDPSimple<Double>) mdp));
					} else {
						model = sortStates ? new MDPSimple<>(mdp, permut) : mdp;
					}
					break;
				case L1MDP:
					model = sortStates ? new L1MDPSimple<>(l1mdp, permut) : l1mdp;
					break;
				case POMDP:
					model = sortStates ? new POMDPSimple<>(pomdp, permut) : pomdp;
					break;
				case CTMDP:
					model = sortStates ? new CTMDPSimple<>(ctmdp, permut) : ctmdp;
					break;
				case CSG:
					model = sortStates ? new CSGSimple<>(csg, permut) : csg;
					break;
				case STPG:
					model = sortStates ? new STPGSimple<>(stpg, permut) : stpg;
					break;
				case SMG:
					model = sortStates ? new SMGSimple<>(smg, permut) : smg;
					break;
				case IDTMC:
					model = sortStates ? new IDTMCSimple<>(idtmc, permut) : idtmc;
					break;
				case IMDP:
					model = sortStates ? new IMDPSimple<>(imdp, permut) : imdp;
					break;
				case IPOMDP:
					model = sortStates ? new IPOMDPSimple<>(ipomdp, permut) : ipomdp;
					break;
				case ICSG:
					model = sortStates ? new ICSGSimple<>(icsg, permut) : icsg;
					break;
				case L1CSG:
					model = sortStates ? new L1CSGSimple<>(l1csg, permut) : l1csg;
					break;
				case LTS:
					model = sortStates ? new LTSSimple<>(lts, permut) : lts;
					break;
				case PTA:
				default:
					throw new PrismNotSupportedException("Model construction not supported for " + modelType + "s");
			}
			model.setStatesList(statesList);
			model.setConstantValues(new Values(modelGen.getConstantValues()));
		}

		permut = null;

		if (!justReach && attachLabels) {
			attachLabels(modelGen, model);
		}

		return model;
	}

	private <Value> void setStateObservation(ModelGenerator<Value> modelGen, PartiallyObservableModel<Value> pomdp, int s, State state) throws PrismException
	{
		State sObs = modelGen.getObservation(state);
		int numVars = modelGen.getNumVars();
		int numUnobsVars = numVars - modelGen.getNumObservableVars();
		State sUnobs = new State(numUnobsVars);
		int count = 0;
		for (int i = 0; i < numVars; i++) {
			if (!modelGen.isVarObservable(i)) {
				sUnobs.setValue(count++, state.varValues[i]);
			}
		}
		pomdp.setObservation(s, sObs, sUnobs, modelGen.getObservableNames());
	}

	private <Value> void attachLabels(ModelGenerator<Value> modelGen, ModelExplicit<Value> model) throws PrismException
	{
		List<State> statesList = model.getStatesList();
		int numStates = statesList.size();
		int numLabels = modelGen.getNumLabels();
		if (numLabels == 0) return;
		BitSet[] bitsets = new BitSet[numLabels];
		for (int j = 0; j < numLabels; j++) {
			bitsets[j] = new BitSet();
		}
		for (int i = 0; i < numStates; i++) {
			State state = statesList.get(i);
			modelGen.exploreState(state);
			for (int j = 0; j < numLabels; j++) {
				if (modelGen.isLabelTrue(j)) {
					bitsets[j].set(i);
				}
			}
		}
		for (int j = 0; j < numLabels; j++) {
			model.addLabel(modelGen.getLabelName(j), bitsets[j]);
		}
	}

	public static void main(String[] args)
	{
		try {
			PrismLog mainLog = new PrismPrintStreamLog(System.out);
			Prism prism = new Prism(mainLog);
			parser.ast.ModulesFile modulesFile = prism.parseModelFile(new File(args[0]));
			UndefinedConstants undefinedConstants = new UndefinedConstants(modulesFile, null);
			if (args.length > 2) {
				undefinedConstants.defineUsingConstSwitch(args[2]);
			}
			modulesFile.setSomeUndefinedConstants(undefinedConstants.getMFConstantValues());
			ConstructModel constructModel = new ConstructModel(prism);
			constructModel.setSortStates(true);
			simulator.ModulesFileModelGenerator<?> modelGen = simulator.ModulesFileModelGenerator.create(modulesFile, constructModel);
			Model<?> model = constructModel.constructModel(modelGen);
			model.exportToPrismExplicitTra(args[1]);
		} catch (FileNotFoundException e) {
			System.out.println("Error: " + e.getMessage());
			System.exit(1);
		} catch (PrismException e) {
			System.out.println("Error: " + e.getMessage());
			System.exit(1);
		}
	}
}