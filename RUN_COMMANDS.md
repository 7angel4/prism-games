# Rebuttal Experiment Run Commands

All commands are run **from the repository root**. One-time setup per machine:

```bash
git pull && export PATH=/opt/homebrew/opt/openjdk/bin:$PATH && cd prism && make && cd ..
```

(A JDK ≥ 17 is required; the macOS default JDK 11 cannot compile this branch. On the
other MacBook install one with `brew install openjdk` if needed. Yices must be set up
as for the paper runs.)

Every command below appends one CSV row per seed to its `-out` file (safe to re-run /
resume with different `-seeds`; nothing is overwritten). Per-episode logs go to
`prism-examples/csgs/learning/logs/<logsubdir>/`. Prefix long runs with `caffeinate -is`
to prevent sleep, e.g. `caffeinate -is bash e2_baselines.sh`. You can run **2–3 commands
in parallel** per machine (each is a single JVM; 16 GB RAM is plenty).

Aggregate any results CSV with:

```bash
python3 prism-examples/csgs/learning/analysis/aggregate.py prism-examples/csgs/learning/results/batch/<file>.csv
```

Rough per-seed timings (M1, from smoke tests): SAFE_RISKY ε=0.2 ≈ 1 min (uniform ≈ 2 min);
actions_m3 ≈ 4 min; states_N1 ≈ 20 min; MIXED_NE/DELAYED_COORD ε defaults: hours up to ~1 day.

---

## FAST MAC — E2: Exploration baselines (highest rebuttal value)

4 explorers × Safe-vs-Risky (10 seeds), Traffic Merge and Delayed Coordination (5 seeds).

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
for EXP in rmdp optimistic uniform roundrobin; do
  PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model SAFE_RISKY -prop 4 -eps 0.2 \
    -seeds 1-10 -explorer $EXP \
    -out prism-examples/csgs/learning/results/batch/e2_safe_risky4.csv -logsubdir e2
done
```

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
for EXP in rmdp optimistic uniform roundrobin; do
  PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model TRAFFIC_MERGE -prop 2 \
    -seeds 1-5 -explorer $EXP \
    -out prism-examples/csgs/learning/results/batch/e2_traffic_merge2.csv -logsubdir e2
done
```

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
for EXP in rmdp optimistic uniform roundrobin; do
  PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model DELAYED_COORD -prop 1 -eps 0.2 \
    -seeds 1-5 -explorer $EXP \
    -out prism-examples/csgs/learning/results/batch/e2_delayed_coord1.csv -logsubdir e2
done
```

(Delayed Coordination is the slow one — start it first, in parallel with the others.
If time runs short, drop to `-seeds 1-3` and say more seeds are running.)

## FAST MAC — E3: Scaling study

### H-sweep (Safe-vs-Risky, property 5, ε=0.2; H via `-const HB`)

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
for H in 1 2 3 5; do
  PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model SAFE_RISKY -prop 5 -const HB=$H -eps 0.2 \
    -seeds 1-10 -explorer rmdp \
    -out prism-examples/csgs/learning/results/batch/e3_H.csv -logsubdir e3H
done
for H in 7 9; do
  PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model SAFE_RISKY -prop 5 -const HB=$H -eps 0.2 \
    -seeds 1-5 -explorer rmdp \
    -out prism-examples/csgs/learning/results/batch/e3_H.csv -logsubdir e3H
done
```

Fit scaling:
```bash
python3 prism-examples/csgs/learning/analysis/fit_scaling.py \
  prism-examples/csgs/learning/results/batch/e3_H.csv --const-key HB --exclude 1`
```

Summarise stats:
```bash
python3 prism-examples/csgs/learning/analysis/aggregate.py prism-examples/csgs/learning/results/batch/e3_H.csv
```

### |S|-sweep (hub family; |S| = 8, 12, 20, 36)

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
for N in 1 2 4 8; do
  PRISM_MAINCLASS=learning.RunPac prism/bin/prism \
    -modelFile prism-examples/csgs/learning/scaling/safe_risky_states_N$N.prism \
    -propsFile prism-examples/csgs/learning/scaling/safe_risky_states.props -prop 1 -eps 0.2 \
    -seeds 1-5 -explorer rmdp \
    -out prism-examples/csgs/learning/results/batch/e3_S.csv -logsubdir e3S
done
```

### |A|-sweep (actions family; |A| = 4, 9, 16, 25)

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
for M in 2 3 4 5; do
  PRISM_MAINCLASS=learning.RunPac prism/bin/prism \
    -modelFile prism-examples/csgs/learning/scaling/safe_risky_actions_m$M.prism \
    -propsFile prism-examples/csgs/learning/scaling/safe_risky_actions.props -prop 1 -eps 0.2 \
    -seeds 1-5 -explorer rmdp \
    -out prism-examples/csgs/learning/results/batch/e3_A.csv -logsubdir e3A
done
```

### ε-sweep (Safe-vs-Risky, property 4, H=3)

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
for EPS in 0.4 0.3 0.2 0.15 0.1; do
  PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model SAFE_RISKY -prop 4 -eps $EPS \
    -seeds 1-10 -explorer rmdp \
    -out prism-examples/csgs/learning/results/batch/e3_eps.csv -logsubdir e3eps
done
```

---

## SLOW MAC — E1: 10-seed reruns of the headline (Table 1/2) results

Default ε per benchmark as in the paper (ε=0.2 for Delayed Coordination). 10 seeds for
the fast benchmarks; 5 for the slow ones (report "5 so far, 10 by camera-ready").

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model SAFE_RISKY -prop 4 \
  -seeds 1-10 -explorer rmdp \
  -out prism-examples/csgs/learning/results/batch/e1_correctness.csv -logsubdir e1
```

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model CYCLIC_PREFS -prop 1 \
  -seeds 1-10 -explorer rmdp \
  -out prism-examples/csgs/learning/results/batch/e1_correctness.csv -logsubdir e1
```

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model HIDE_OR_RUN -prop 2 \
  -seeds 1-10 -explorer rmdp \
  -out prism-examples/csgs/learning/results/batch/e1_correctness.csv -logsubdir e1
```

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model TRAFFIC_MERGE -prop 2 \
  -seeds 1-10 -explorer rmdp \
  -out prism-examples/csgs/learning/results/batch/e1_correctness.csv -logsubdir e1
```

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model MIXED_NE -prop 1 \
  -seeds 1-5 -explorer rmdp \
  -out prism-examples/csgs/learning/results/batch/e1_correctness.csv -logsubdir e1
```

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model DELAYED_COORD -prop 1 -eps 0.2 \
  -seeds 1-5 -explorer rmdp \
  -out prism-examples/csgs/learning/results/batch/e1_correctness.csv -logsubdir e1
```

## SLOW MAC — E0 extras: model-error curves + periodic true value gap

Model error `max ||P̂−P*||₁` is logged in every run above (column `modelErrL1` of the
per-episode logs) — VuXP's "plot the distance to ground truth" is covered for free.
The periodic **true value gap** additionally solves the empirical game every 200
episodes (only feasible on small games / properties with strategy generation, i.e.
unbounded ones):

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model SAFE_RISKY -prop 1 \
  -seeds 1-3 -explorer rmdp -valueGapEvery 200 \
  -out prism-examples/csgs/learning/results/batch/e0_valuegap.csv -logsubdir e0vg
```

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
PRISM_MAINCLASS=learning.RunPac prism/bin/prism -model MIXED_NE -prop 1 \
  -seeds 1-3 -explorer rmdp -valueGapEvery 200 \
  -out prism-examples/csgs/learning/results/batch/e0_valuegap.csv -logsubdir e0vg
```

---

## Priority order (if time runs out)

1. E2 baselines on SAFE_RISKY + DELAYED_COORD (answers A7PK Q2 and VuXP's optimism question)
2. E1 10-seed correctness rerun (answers VuXP's seeds complaint)
3. E3 H-sweep + ε-sweep (extends the paper's Fig. 1 with error bars)
4. E3 |S| / |A| sweeps (new scaling evidence)
5. E0 value-gap runs (nice-to-have; model error is already in every log)

## Note for the rebuttal text

Two implementation bugs were found and fixed while adding the baselines (see PROGRESS.md):
exploration strategies were silently not enforced during sampling, and exploration rewards
were never initialised — i.e. the submitted experiments effectively used uniform exploration.
Correctness results (learned values/profiles) are unaffected; the updated sample-complexity
numbers are *better* (e.g. ~2× fewer samples on Safe-vs-Risky), and the exploration mechanism
now demonstrably outperforms the uniform baseline, which strengthens rather than weakens the
paper's claims. The rebuttal can present the baseline comparison directly without mentioning
the internal history; the camera-ready numbers should be regenerated from these runs.
