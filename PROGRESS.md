# Rebuttal Experiments — Progress & Changelog

Working notes for the NeurIPS rebuttal experiments (deadline Aug 3).
Each entry corresponds to one committed logical step.

## Goals (mapped to reviews)

- **E0 — Infrastructure**: CLI batch runner over benchmarks/seeds/explorers with machine-readable
  CSV output; per-episode logging of the true model error `max_(s,a) ||P̂_t − P*||₁` and (optionally)
  the periodic true value gap `V* − u(σ̂_t, P*)`. *(VuXP: "why is the distance between ground truth
  and learned estimates not plotted?", "can't the value gap be computed every X episodes?")*
- **E1 — Seeds**: rerun headline results with ≥10 seeds (5 for the slowest runs), report error bars.
  *(VuXP: "only three seeds … insufficient")*
- **E2 — Exploration baselines**: compare the paper's robust-RMDP exploration against
  uniform-random, round-robin state–joint-action targeting, and optimistic exploration.
  *(A7PK Q2; VuXP: "why is exploration pessimistic? would optimistic exploration help?")*
- **E3 — Scaling study**: vary |S|, |A|, H independently on parameterized Safe-vs-Risky families,
  compare empirical samples-to-convergence against the theoretical H⁴|S||A| scaling.
  *(A7PK Q4; VuXP/CDBV: "toy problems / scalability untested")*

## Changelog

### 2026-07-23 — E0 + E2 implementation (+ two significant bug fixes)

**New files**
- `prism/src/learning/RunPac.java` — CLI batch runner. One CSV row per run
  (episodes, samples, transitions, runtime, values, value gaps, …). Supports `-model` enum names or
  explicit `-modelFile/-propsFile`, seed lists/ranges, `-explorer`, `-const`, `-valueGapEvery`.
- `prism/src/learning/ExplorationPolicy.java` — `rmdp` (paper's algorithm, default), `optimistic`,
  `uniform`, `roundrobin`.

**Modified**
- `PACLearner` — exploration-policy dispatch; per-episode true-model-error logging; optional
  periodic true value gap; transition counting (answers A7PK Q3: samples = trajectories,
  transitions also reported); `RunSummary`/`summarise()` for batch output; exploration-reward
  initialisation fix (below).
- `PACHelper` — exploration strategies are now enforced **by choice index** during trajectory
  sampling (memory = elapsed steps, matching `FMDStrategyStep`), with successor sampling from the
  simulator's transition probabilities. Uniform baseline = no strategy.
- `Logger` — run labels (no overwrites in batch runs) + `modelErrL1`, `trueValueGap` columns.
- `Experiment` — constructor for explicit model/props file paths.
- `L1MDPSimple` — copy model metadata (states list) in the `L1CSGSimple` constructor (bug fix #1).

**Bug fix #1 — exploration strategies were never enforced.**
`L1MDPSimple(L1CSGSimple)` did not copy the states list, so strategies solved on the exploration
RMDP could not look up states; the simulator silently fell back to uniform-random choices.
Additionally, even with the states list fixed, strategy enforcement via simulator action labels
fails for CSG joint actions (label format mismatch, e.g. `[,a2]`). Exploration is now enforced by
choice index inside `PACHelper.sampleTrajectory` (the same indexing the slot counts use).

**Bug fix #2 — exploration rewards were never initialised.**
`update()` only refreshes rewards of slots with ≥1 sample, and rewards start at 0, so never-visited
slots never attracted the exploration strategy; with enforcement fixed, the RMDP then maximised an
all-zero objective (ties → always choice 0) and learning stalled. All slots now start with reward
`INIT_RADIUS²`.

**Net effect (Safe-vs-Risky, prop 4, ε=0.2, seed 41):** the paper's exploration mechanism now works
as designed and *beats* uniform exploration ~2×:

| explorer | episodes | samples | runtime |
|---|---|---|---|
| rmdp (paper) | 45,186 | 5.89M | 54s |
| optimistic | 45,186 | 5.89M | 53s |
| roundrobin | 21,160 | 6.04M | 55s |
| uniform | 90,152 | 11.61M | 104s |

(Previously all four were bitwise-identical — exploration had no effect at all.)
Robust values unchanged (≈0.9949 ≈ paper's result), so prior correctness results stand;
prior *sample-complexity* numbers were effectively measured under uniform exploration.

**Verified:** value-gap logging on MIXED_NE (V*=1.0, gap 0.0 from episode 1); all four explorers
converge on SAFE_RISKY; existing `PACLearner.main` entry point unchanged.

**Build note:** compile with a JDK ≥ 17 (e.g. `export PATH=/opt/homebrew/opt/openjdk/bin:$PATH`),
then `cd prism && make`. The default CLI JDK 11 on this machine cannot compile the branch.

## TODO
- [x] E0 batch runner + logging
- [x] E2 exploration baselines
- [ ] E3 scaling model families (|S|, |A|) + generator
- [ ] Final run commands per machine (RUN_COMMANDS.md)
