# Robust PAC Learning of Concurrent Stochastic Games

Code for the NeurIPS 2026 paper:

> Angel Y. He and David Parker. **Robust PAC Learning of Concurrent Stochastic Games.**
> In *Advances in Neural Information Processing Systems (NeurIPS)*, 2026.

- Paper: [arXiv:2609.04189](https://arxiv.org/abs/2609.04189)
- Project page: <https://7angel4.github.io/robust-pac-csg/>

This branch (`csg-learning`) of [PRISM-games](https://www.prismmodelchecker.org/games/) adds a PAC learner
for two-player general-sum concurrent stochastic games (CSGs) whose transition probabilities are unknown.
Under the paper's assumptions, with probability at least 1 − δ, the learner returns either an ε-Nash
equilibrium (ε-NE), which is also ε-optimal when an exact stationary NE exists, or a sound certificate that
no exact stationary NE exists.

The branch contains:

- the PAC-CSG loop (Algorithm 1 of the paper): L1 confidence sets built from sampled trajectories, robust
  equilibrium computation on the learned model, and an exploration RMDP (robust Markov decision process)
  that steers sampling towards under-visited state–action pairs;
- the baseline explorers compared in RQ2: optimistic, uniform-random and round-robin;
- `RunPac`, a command-line batch runner;
- the six benchmark games and their properties, the results CSVs behind the paper's tables and figures,
  and the analysis scripts.

## Repository layout

| Path | Contents |
| --- | --- |
| `prism/src/learning/PACLearner.java` | The PAC learning loop (Algorithm 1) |
| `prism/src/learning/RunPac.java` | Command-line batch runner: one CSV row per seed |
| `prism/src/learning/Experiment.java` | Benchmark and experiment configuration (the names accepted by `-model`) |
| `prism/src/learning/ExplorationPolicy.java` | Explorers: `rmdp` (the paper's algorithm), `optimistic`, `uniform`, `roundrobin` |
| `prism/src/learning/PACHelper.java` | Trajectory sampling and utility routines |
| `prism/src/learning/Logger.java` | Per-episode logs |
| `prism/src/explicit/L1CSGSimple.java` | The learned model: a CSG with an L1 radius on each choice |
| `prism/src/explicit/L1MDPSimple.java` | The exploration RMDP |
| `prism/src/explicit/UCSGModelChecker.java` | Robust CSG model checking |
| `prism-examples/csgs/learning/` | The six benchmarks (`*.prism`) and their rPATL properties (`*.props`) |
| `prism-examples/csgs/learning/scaling/` | Safe vs. Risky variants for the scaling study (RQ3) and `generate_models.py` |
| `prism-examples/csgs/learning/results/batch/` | Results CSVs written by `RunPac` |
| `prism-examples/csgs/learning/analysis/` | `aggregate.py`, `fit_scaling.py`, `plot_scaling.py` and the plots |

## Benchmarks

| `-model` | Benchmark | What it tests |
| --- | --- | --- |
| `SAFE_RISKY` | Safe vs. Risky | Actions that differ in how sensitive they are to the transition probabilities |
| `TRAFFIC_MERGE` | Traffic Merge | Safety-critical multi-step coordination |
| `HIDE_OR_RUN` | Hide-or-Run | No stationary NE: the value is reached only in the limit |
| `MIXED_NE` | Mixed NE | The equilibrium is mixed |
| `DELAYED_COORD` | Delayed Coordination | Hard exploration under sparse, delayed rewards |
| `CYCLIC_PREFS` | Cyclic Preferences | No exact stationary NE for the reachability-reward property |

Each model comes with finite- and infinite-horizon properties in rPATL, for example
`<<p1:p2>>max=? ( P[F s=1] + P[F s=2] )`. `-prop N` selects the N-th property in the `.props` file,
counting properties only (constant declarations are skipped).

## Requirements

- JDK 17 or later. An older JDK (such as JDK 11) cannot compile this branch.
- Yices, the SMT solver that PRISM-games uses here for equilibrium computation (`RunPac` uses it by default).
  For setting up PRISM-games and its solvers, see the
  [PRISM-games installation page](http://www.prismmodelchecker.org/games/installation.php).
- Python 3 for the analysis scripts. `plot_scaling.py` also needs matplotlib and a LaTeX installation,
  because it uses matplotlib's PGF backend.

The paper's experiments ran on an Apple M4 Pro with 24 GB RAM. Peak memory stayed below 0.5 GB; the
largest configuration took about 5 hours per seed.

## Building

```bash
git clone https://github.com/7angel4/prism-games.git
cd prism-games
git checkout csg-learning
cd prism && make && cd ..
```

If your default Java is older than 17, put a newer JDK first on your `PATH` before running `make`
(with Homebrew on macOS: `export PATH=/opt/homebrew/opt/openjdk/bin:$PATH`).

## Running the learner

Run `RunPac` from the repository root through the `prism` launcher:

```bash
PRISM_MAINCLASS=learning.RunPac prism/bin/prism \
  -model SAFE_RISKY -prop 4 -eps 0.2 -seeds 1-10 -explorer rmdp \
  -out prism-examples/csgs/learning/results/batch/safe_risky4.csv -logsubdir batch
```

Each seed appends one row to the `-out` CSV, so re-running with other seeds adds rows and overwrites
nothing. Per-episode logs go to `prism-examples/csgs/learning/logs/<logsubdir>/`.

| Option | Meaning (default) |
| --- | --- |
| `-model NAME` | Benchmark name from the table above; or give `-modelFile` and `-propsFile` |
| `-modelFile PATH`, `-propsFile PATH` | Explicit `.prism` and `.props` files |
| `-prop N` | 1-based property index (the benchmark's default property) |
| `-eps X` | ε (R_max/10, where R_max is the largest absolute reward in the model, or 1 if it has none) |
| `-delta X` | δ (0.05) |
| `-seeds SPEC` | Seeds, e.g. `1,2,5` or `1-10` (1) |
| `-explorer NAME` | `rmdp`, `optimistic`, `uniform` or `roundrobin` (`rmdp`) |
| `-const n=v,...` | Model or property constants, e.g. `HB=7` |
| `-out PATH` | Results CSV to append to (`prism-examples/csgs/learning/results/batch/<model><prop>.csv`) |
| `-logsubdir NAME` | Subfolder of `logs/` for per-episode logs (`batch`) |
| `-valueGapEvery N` | Every N episodes, solve the learned game and log its value gap in the true game (0, off) |
| `-solver NAME` | SMT solver (`Yices`) |
| `-exportStrat` | Export the learned strategies to files (off) |

`-robustness` and `-maxSamples` are for robustness experiments that are not part of the paper; the Javadoc
of `RunPac.java` describes all options. `PACLearner.main()` runs a single configuration that is set in the
source, which is convenient in an IDE.

## Reproducing the paper's results

The paper uses δ = 0.05 and 10 seeds throughout, and ε = 0.2 except in RQ1, which uses ε = 0.1 (ε = 0.2 for
Delayed Coordination's unbounded-reachability property). The CSVs in
`prism-examples/csgs/learning/results/batch/` hold the runs behind the paper's numbers. The commands below
write to new files, so they do not mix with those runs.

```bash
runpac() { PRISM_MAINCLASS=learning.RunPac prism/bin/prism "$@"; }
OUT=prism-examples/csgs/learning/results/batch
```

**RQ1 (Tables 1 and 3): correctness.** These are the 13 properties of Table 3.

```bash
while read -r MODEL PROP EPS; do
  runpac -model "$MODEL" -prop "$PROP" -eps "$EPS" -seeds 1-10 -out $OUT/rq1.csv -logsubdir rq1 < /dev/null
done <<'END'
CYCLIC_PREFS 1 0.1
CYCLIC_PREFS 2 0.1
DELAYED_COORD 1 0.2
DELAYED_COORD 2 0.1
HIDE_OR_RUN 1 0.1
HIDE_OR_RUN 2 0.1
MIXED_NE 1 0.1
MIXED_NE 2 0.1
SAFE_RISKY 1 0.1
SAFE_RISKY 2 0.1
SAFE_RISKY 3 0.1
TRAFFIC_MERGE 1 0.1
TRAFFIC_MERGE 2 0.1
END
```

Table 1 shows eight of them: `CYCLIC_PREFS 1`, `DELAYED_COORD 2`, `HIDE_OR_RUN 1` and `2`, `MIXED_NE 1`,
`SAFE_RISKY 1` and `3`, and `TRAFFIC_MERGE 2`.

**RQ2 (Table 2): exploration strategies.**

```bash
for MP in "SAFE_RISKY 4" "TRAFFIC_MERGE 2" "CYCLIC_PREFS 1" "DELAYED_COORD 1"; do
  read -r MODEL PROP <<< "$MP"
  for EXP in rmdp optimistic uniform roundrobin; do
    runpac -model $MODEL -prop $PROP -eps 0.2 -seeds 1-10 -explorer $EXP -out $OUT/rq2.csv -logsubdir rq2
  done
done
```

**RQ3 (Figure 1): sample-complexity scaling** in Safe vs. Risky. All parameters other than the varied one
stay at |S| = 4, |A| = 4, H = 3 and ε = 0.2.

```bash
S=prism-examples/csgs/learning/scaling
# |S| = 4c + 4 = 8, 12, 20, 36, 60 (the |S| = 4 point is the base game: SAFE_RISKY, property 4)
for N in 1 2 4 8 14; do
  runpac -modelFile $S/safe_risky_states_N$N.prism -propsFile $S/safe_risky_states.props -prop 1 -eps 0.2 \
    -seeds 1-10 -out $OUT/rq3_S.csv -logsubdir rq3S
done
# |A| = m^2 = 4, 9, ..., 64 (m = 2 is the base game)
for M in 2 3 4 5 6 7 8; do
  runpac -modelFile $S/safe_risky_actions_m$M.prism -propsFile $S/safe_risky_actions.props -prop 1 -eps 0.2 \
    -seeds 1-10 -out $OUT/rq3_A.csv -logsubdir rq3A
done
# H = 1, ..., 15
for H in 1 2 3 5 7 9 11 15; do
  runpac -model SAFE_RISKY -prop 5 -const HB=$H -eps 0.2 -seeds 1-10 -out $OUT/rq3_H.csv -logsubdir rq3H
done
# epsilon = 0.05, ..., 0.4
for EPS in 0.4 0.3 0.2 0.15 0.1 0.05; do
  runpac -model SAFE_RISKY -prop 4 -eps $EPS -seeds 1-10 -out $OUT/rq3_eps.csv -logsubdir rq3eps
done
```

## Analysing the results

From the repository root:

```bash
A=prism-examples/csgs/learning/analysis
OUT=prism-examples/csgs/learning/results/batch
# mean, standard deviation and 95% CI per configuration
python3 $A/aggregate.py $OUT/rq2.csv
# log-log fits: exponent and R^2
python3 $A/fit_scaling.py $OUT/rq3_H.csv --const-key HB
python3 $A/fit_scaling.py $OUT/rq3_eps.csv --column epsilon --invert
python3 $A/fit_scaling.py $OUT/rq3_A.csv --model-regex 'safe_risky_actions_m(\d+)' --transform 'lambda m: m**2'
python3 $A/fit_scaling.py $OUT/rq3_S.csv --model-regex 'safe_risky_states_N(\d+)' --transform 'lambda c: 4*c + 4'
# plot with error bars and the fitted power law
python3 $A/plot_scaling.py $OUT/rq3_H.csv rq3_H.pdf --xlabel '$H$' --const-key HB
```

`plot_scaling.py --extra-point X:MEAN:STD:N` adds a point from another run, such as the |S| = 4 base game.
`fit_scaling.py -h` and `plot_scaling.py -h` list all options.

## Citation

```bibtex
@inproceedings{he2026robust,
  title     = {Robust {PAC} Learning of Concurrent Stochastic Games},
  author    = {He, Angel Y. and Parker, David},
  booktitle = {Advances in Neural Information Processing Systems (NeurIPS)},
  year      = {2026}
}
```

## Licence

This code extends PRISM-games, which is distributed under the GNU General Public License, version 2
(see `COPYING.txt`). The original PRISM-games README is in `README-orig.md`.
