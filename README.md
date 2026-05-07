# Robust PAC Learning of Concurrent Stochastic Games

This repository contains the implementation accompanying the NeurIPS 2026 submission:

> **Robust PAC Learning of Concurrent Stochastic Games**

The implementation extends the PRISM-games probabilistic model checker with:
- PAC learning for concurrent stochastic games (CSGs),
- robust equilibrium computation over L1 uncertainty sets,
- exploration via robust MDPs,
- benchmark suites and reproducibility scripts.

The repository is currently anonymised for double-blind review.

---

# Overview

This implementation provides:
- a PAC-learning framework for two-player general-sum CSGs,
- robust equilibrium computation using L1 confidence sets,
- online trajectory sampling and uncertainty updates,
- exploration using an auxiliary robust MDP,
- support for finite- and infinite-horizon objectives,
- benchmark environments used in the paper.

The implementation builds on the PRISM-games framework:
- PRISM-games: https://www.prismmodelchecker.org/games/

---

# Repository Structure

```text
prism/
├── src/
│   ├── learning/                  # Main PAC-CSG implementation
│   │   ├── PACLearner.java
│   │   ├── Experiment.java
│   │   ├── PACHelper.java
│   │   └── ...
│   │
│   ├── explicit/                  # Extensions to robust model checking
│   ├── strat/
│   └── ...
│
prism-examples/
├── csgs/
│   └── learning/                  # Benchmark suite and property specifications
│       ├── *.prism
│       ├── *.props
│       └── ...
````

---

# Main Contributions in This Branch

This branch extends PRISM-games with:

* PAC learning for concurrent stochastic games,
* L1 uncertainty sets over transition kernels,
* robust equilibrium-based planning,
* exploration RMDP construction,
* online trajectory sampling and coverage tracking,
* benchmark environments for robust PAC-CSG evaluation.

The core implementation is located in:

```text
prism/src/learning/
```

---

# Requirements

Tested on:

* macOS (Apple M1)
* Java 17
* PRISM-games 3.x
* Yices SMT solver

Required dependencies:

* Java 17+
* PRISM-games native libraries
* Yices SMT solver

---

# Building

Clone the repository:

```bash
git clone <repo-url>
cd prism-games
git checkout csg-learning
```

Build PRISM-games:

```bash
make
```

or using the PRISM build system:

```bash
cd prism
make
```

Ensure native libraries are enabled before running experiments.

---

# Running Experiments

Experiments are executed through:

```text
prism/src/learning/PACLearner.java
```

A typical experiment configuration:

```java
Experiment ex = new Experiment(Experiment.CaseStudy.SAFE_RISKY);

ex.setSolverString("Yices");
ex.propertyIndex = 4;
ex.epsilon = 0.2;

Experiment.PacRunSpec spec = ex.buildPacRunSpec(prism);

PACLearner learner = new PACLearner(prism, 41, true);

PacResult res = learner.runPacLoop(
    spec,
    ex.modelFile,
    ex.propertyIndex,
    false,
    "eps"   # subdirectory for logging
);
```

Experiments are launched via the `main()` method in:

```text
prism/src/learning/PACLearner.java
```

---

# Benchmark Suite

Benchmark models and property specifications are located in:

```text
prism-examples/csgs/learning/
```

The benchmark suite includes:

| Benchmark            | Purpose                                          |
| -------------------- | ------------------------------------------------ |
| SAFE_RISKY           | Robust coordination under uncertainty            |
| TRAFFIC_MERGE        | Multi-step coordination with crash risk          |
| HIDE_OR_RUN          | Limit equilibria / no stationary optimal profile |
| MIXED_NE             | Mixed Nash equilibrium behaviour                 |
| DELAYED_COORD        | Sparse delayed rewards and exploration           |
| CYCLIC_PREFS / NO_NE | Stationary equilibrium non-existence                        |

These correspond to the benchmark environments described in Section 5 of the paper. 

---

# Property Specifications

Properties are written in rPATL and stored in `.props` files alongside the benchmark models.

Examples include:

* bounded probabilistic reachability,
* bounded cumulative reward,
* unbounded probabilistic reachability
* unbounded reachability reward (stochastic shortest path objectives),

in both zero-sum and nonzero-sum settings.

Example property (unbounded probabilistic reachability):

```text
<<p1:p2>>max=? ( P[F s1] + P[F s2] )
```

---

# Reproducing Paper Results

The experiments in:

* Table 1,
* Table 2,
* Figure 1

can be reproduced by configuring the corresponding benchmark and property index in `PACLearner.java`.

Experimental settings used in the paper:

* confidence parameter: `δ = 0.05`
* default `ε = 0.1`; but `ε = 0.2` for DELAYED_COORD andd RQ2's experiments
* three random seeds
* Apple M1 CPU / 16GB RAM

See Section 5 and Appendix K of the paper for details. 

---

# Output and Logs

The learner reports:

* total no. of episodes,
* total no. of samples,
* robust equilibrium value,
* point-estimate value,
* uncertainty radius statistics,
* equilibrium existence status.

Important quantities:

* `deltaT` — model uncertainty proxy Δ_t
* `NOT_FOUND` — no robust equilibrium found under the current uncertainty set
* `known slots` — state-action pairs sufficiently explored

Logs are written during execution and can be used to reproduce:

* convergence plots,
* uncertainty curves,
* sample complexity measurements.

---

# PAC-CSG Pipeline

The implementation follows Algorithm 1 from the paper:

1. Construct empirical L1-CSG from sampled trajectories
2. Build exploration RMDP
3. Solve robust equilibrium
4. Execute exploration profile
5. Update transition uncertainty sets
6. Repeat until stopping criterion is met

See:

* Section 4,
* Algorithm 1,
* Appendix J

for theoretical and algorithmic details. 

---

# Important Source Files

| File               | Purpose                                |
| ------------------ | -------------------------------------- |
| `PACLearner.java`  | Main PAC learning loop                 |
| `Experiment.java`  | Benchmark and experiment configuration |
| `PACHelper.java`   | Sampling and utility routines          |
| `UCSGModelChecker` | Robust CSG model checking extensions       |
| `L1CSGSimple`      | Empirical L1-CSG representation      |
| `L1MDPSimple`      | Exploration RMDP representation        |

---

# Reproducibility Notes

This repository is intended to satisfy the NeurIPS reproducibility checklist:

* implementation included,
* benchmark suite included,
* property specifications included,
* exact experimental parameters documented,
* compute environment documented,

All experiments are reproducible from the provided implementation and benchmark specifications.

---

# Licensing and Acknowledgements

This implementation extends PRISM-games, which is distributed under the GNU GPL v2 license.

Relevant references:

* PRISM-games 3.0
* Robust CSG verification
* PAC learning and robust reinforcement learning literature

See the paper bibliography for full citations. 
