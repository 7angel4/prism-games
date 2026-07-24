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

### 2026-07-23 — E3 scaling families + run commands

- `prism-examples/csgs/learning/scaling/generate_models.py` + generated families:
  `safe_risky_states_N{1,2,4,8}` (|S| = 8..36, hub variant, fixed H=3, values/equilibria
  preserved) and `safe_risky_actions_m{2..5}` (|A| = 4..25; m=2 recovers the original game).
- `safe_risky.props` property 5: H-sweep via undefined constant `HB` (`-const HB=<h>`),
  replacing hand-editing of `k`.
- `analysis/aggregate.py`: mean/std/95% CI tables from batch CSVs.
- `RUN_COMMANDS.md`: exact commands for both MacBooks, priority-ordered.
- Verified: actions_m3 converges (12.0M samples vs 5.9M at m=2 — clean |A| scaling signal);
  states_N1 converges; prop 5 H-sweep works (HB=2: 2.0M samples, 13s) and prop 4 unaffected.

### 2026-07-24 — Bug fix #3: stale-reward exploration deadlock (N=1 |S|-sweep anomaly)

Symptom: `safe_risky_states_N1` was slower than N4 — the run froze with the 4 medium-state
slots unknown and `maxRadius` bit-identical for 465k+ episodes (zero samples reaching them).

Root cause: in `update()`, `updateExplorationReward` ran *before* `updateKnown`, so on the
episode a slot flips to known it keeps its stale positive reward; the exploration RMDP is only
re-solved *on flips*, i.e. every re-solve saw the just-flipped slot's stale reward. When that
stale value exceeded the (reachability-discounted) value of the remaining unknown slots, the
new strategy deterministically targeted the already-known slot; with no further flips there was
no further re-solve — a permanent starvation loop. N1's two-hop route to `medium`
(s0 → channel → medium, discount 0.4) made it the reliable victim; other benchmarks escaped by
terminating via the Δ_t condition first (at some endgame-sample cost).

Fix: swap the two calls (`updateKnown` before `updateExplorationReward`). One line.
Consequence: **all E3 sweeps (and E2 rmdp/optimistic runs) should be rerun on the fixed build**
for internally-consistent numbers — completed runs are *correct* (values match oracle) but their
sample totals include stale-endgame waste and will improve after the fix.

### Results snapshot (pre-fix builds, values verified correct)

- **E2**: uniform needs 2× (Safe-vs-Risky), 2.4× (Cyclic Prefs), **17×** (Delayed Coord) more
  samples than rmdp — and on Delayed Coord converges to a worse robust value (1.80 vs 2.00).
  Round-robin comparable on small games, 2.2× worse on Traffic Merge. Optimistic ≈ pessimistic
  in samples, but on Delayed Coord its learned value is worse on some seeds (1.92 ± 0.14 vs
  2.000 ± 0.000) — pessimism costs nothing here and is safer.
- **E1** (10 seeds fast / 5 slow): all correctness results reproduce with negligible variance;
  Cyclic Prefs returns the non-existence certificate in 10/10 seeds; Delayed Coord: robust
  profile optimal in 4/5 seeds while the point-estimate profile fails (gap ≈ V*/2) in 4/5.
- **E3**: ε-sweep slope ≈ 2.1 in log-log (theory 1/ε²); |A|-sweep slope ≈ 0.89 (theory ≤ 1);
  H-sweep polynomial; |S|-sweep to be rerun post-fix.

## TODO
- [x] E0 batch runner + logging
- [x] E2 exploration baselines
- [x] E3 scaling model families (|S|, |A|) + generator, H-sweep property
- [x] Run commands per machine (RUN_COMMANDS.md)
- [ ] Runs executed on both machines (user)
- [ ] Aggregate results + rebuttal figures
