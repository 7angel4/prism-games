# Artifact for "Robust Verification of Concurrent Stochastic Games"

This artifact accompanies the TACAS 2026 paper:

> **Robust Verification of Concurrent Stochastic Games**

It provides an implementation of **Interval Concurrent Stochastic Games (ICSGs)**
and robust verification algorithms, integrated into the **PRISM-games** model
checker.

---

## Link to Artifact

https://github.com/7angel4/prism-games/tree/tacas-26

This artifact corresponds to a development branch of PRISM-games that extends the
tool with support for model checking **two-player ICSGs**, and includes scripts
for reproducing the main experimental results reported in the TACAS 2026 paper.

---

## System Requirements

### Operating System
- **macOS (ARM64)** — used for the experiments reported in the paper  
- **Linux (x86_64)** — supported, but execution times may differ

### Software
- **Java:** OpenJDK 23 (or compatible)

### Hardware
- Recommended RAM: **≥ 16 GB**
- Minimum RAM for smoke test: **8 GB**

---

## Build Instructions

1. Clone the artifact repository:
```
git clone https://github.com/7angel4/prism-games.git
```
2. Checkout the artifact branch:
```
cd prism-games
git checkout tacas-26
```
3. Build PRISM-games:
```
cd prism
make
```
4. (Recommended) Configure Java heap size:
```
export PRISM_JAVA_ARGS="-Xms4G -Xmx16G"
```

The PRISM executable will be located at:
```
prism-games/prism/bin/prism
```

---

## Early Smoke Test (Example Commands)

- **Expected time:** 1–2 minutes  

Run the following commands from the `prism-games/prism` directory.

### Zero-sum ICSG vs CSG

**Case study:** Jamming Radio Systems (Property 1)

ICSG:
```
bin/prism 
../prism-examples/csgs/jamming/jamming4_icsg.prism 
../prism-examples/csgs/jamming/jamming.props 
-prop 1 -const chans=4,slots=6,eps=0.01
```

CSG:
```
bin/prism 
../prism-examples/csgs/jamming/jamming4.prism 
../prism-examples/csgs/jamming/jamming.props 
-prop 1 -const chans=4,slots=6
```

---

### Nonzero-sum ICSG vs CSG

**Case study:** Aloha (Deadline)

ICSG:
```
bin/prism 
../prism-examples/csgs/aloha/aloha_backoff3_icsg.prism 
../prism-examples/csgs/aloha/aloha_backoff3.props 
-prop 7 -smtsolver yices 
-const bcmax=1,D=8,q=0.9,eps=1/257
```

CSG:
```
bin/prism 
../prism-examples/csgs/aloha/aloha_backoff3.prism 
../prism-examples/csgs/aloha/aloha_backoff3.props 
-prop 7 -smtsolver yices 
-const bcmax=1,D=8,q=0.9
```

---

### Expected Outcome

For each command:

- the model is parsed successfully;
- (robust) verification is performed;
- a log is printed containing:
  1. the numerical value of the selected property, and  
  2. the verification time.

---

### General Command Pattern

All experiments in the paper follow the pattern:
```
bin/prism <model>.prism <properties>.props 
-prop <property_number> -const <parameters>
```

---

## Reproducing the Main Experimental Results

All scripts and data for reproducing the experimental results in the paper are
located in the directory:
```
prism/icsg-tests/
```

### Directory Structure

```
prism/icsg-tests
├─ scripts
│  ├─ zero-sum
│  └─ nonzero-sum
├─ logs
│  ├─ zero-sum
│  └─ nonzero-sum
└─ results
   ├─ zero-sum
   └─ nonzero-sum
```

- **scripts/**  
  Shell scripts used to run the experiments for each case study.

- **logs/**  
  Raw log outputs produced by running the scripts.

- **results/**  
  CSV files containing extracted results.  
  Each CSV reports both **CSG** and **ICSG** results and matches the data
  reported in **Table 1-2 and Figure 1-2** of the paper.

Each of the `zero-sum/` and `nonzero-sum/` subdirectories contains scripts and
results corresponding to the zero-sum and nonzero-sum experiments, respectively.
Scripts are named after the corresponding case studies.

---

### Running All Experiments

The following scripts run **all experiments** reported in the main text:

- Zero-sum experiments (Table 1, Figure 2):
```
prism/icsg-tests/scripts/zero-sum/run_all.sh
```

- Nonzero-sum experiments (Table 2, Figure 1):
```
prism/icsg-tests/scripts/nonzero-sum/run_all.sh
```

⚠️ **Note:** Running all experiments can take a **very long time** (hours to
days, depending on hardware).  

We therefore recommend running **individual case-study scripts** instead.

---

### Running Individual Case Studies

Each case study has its own script in the corresponding directory, e.g.:

```
prism/icsg-tests/scripts/zero-sum/aloha.sh
```

So to test the zero-sum Aloha case study in Table 1, 
simply run the following command from the `prism-games/prism` directory:
```
./icsg-tests/scripts/zero-sum/aloha.sh
```

Some case studies evaluate multiple parameter values and may still be
time-consuming. To reduce runtime, you can limit the parameter ranges at the
top of the script.

**Example:** In
```
scripts/zero-sum/aloha.sh
```
change:
```
BMAX_VALS=(2 3 4 5)
```
to:
```
BMAX_VALS=(2 3 4)
```
since `bmax=5` can take up to ~1.5 hours to complete.

---

## Notes on Reproducibility

- **Numerical results** (values and probabilities) are deterministic, up to minor
  floating-point rounding differences.
- **Execution time** may vary due to:
  - hardware differences,
  - JVM warm-up and solver initialisation (the first run is typically the slowest),
  - available memory.
- These variations do **not** affect correctness or the conclusions of the paper.