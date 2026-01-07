# Artifact for "Robust Verification of Concurrent Stochastic Games

This artifact accompanies the TACAS 2026 paper:

> **Robust Verification of Concurrent Stochastic Games**

It provides an implementation of **Interval Concurrent Stochastic Games (ICSGs)**
and robust verification algorithms, integrated into the **PRISM-games** model
checker.

---

## Link to Artifact
https://github.com/7angel4/prism-games/tree/tacas-26

This artifact corresponds to a development branch of PRISM-games that extends the
tool with support for model-checking two-player ICSGs.

---

## System Requirements

### Operating System
- **macOS (ARM64)** — used for the experiments reported in the paper  
- Linux (x86_64) is expected to work but has not been extensively tested

### Software
- **Java:** OpenJDK 23 (or compatible)

### Hardware
- Recommended RAM: **≥ 16 GB**
- Minimum RAM for smoke test: **8 GB**

---

## Command-Line Interface

### Build Instructions

1. Unzip the artifact archive.
2. Navigate to the PRISM-games directory:
```
cd prism-games-two-player-icsg
```
3. Compile PRISM-games following the standard instructions:
```
make
```
The resulting executable will be located in:
```
prism-games/prism/bin/prism
```

---

### Early Smoke Test (Example Commands)

- **Expected time:** 1-2 minutes  

Run the following example commands from the `prism-games/prism` directory.

#### Environmental Variables

Before running, set Java heap size using:
```
export PRISM_JAVA_ARGS="-Xms4G -Xmx16G"
```

If running on macOS, also set:
```
export DYLD_LIBRARY_PATH=lib
```

---


#### Zero-sum ICSG vs CSG

**Case study**: Jamming Radio Systems (Property 1)

ICSG:
```
bin/prism \
../prism-examples/csgs/jamming/jamming4_icsg.prism \
../prism-examples/csgs/jamming/jamming.props \
-prop 1 -const chans=4,slots=6,eps=0.01
```

CSG:
```
bin/prism \
../prism-examples/csgs/jamming/jamming4.prism \
../prism-examples/csgs/jamming/jamming.props \
-prop 1 -const chans=4,slots=6
```

---

#### Nonzero-sum ICSG vs CSG

**Case study**: Aloha (Deadline)

ICSG:
```
bin/prism \
../prism-examples/csgs/aloha/aloha_backoff3_icsg.prism \
../prism-examples/csgs/aloha/aloha_backoff3.props \
-prop 7 -smtsolver yices \
-const bcmax=1,D=8,q=0.9,eps=1/257
```

CSG:
```
bin/prism \
../prism-examples/csgs/aloha/aloha_backoff3.prism \
../prism-examples/csgs/aloha/aloha_backoff3.props \
-prop 7 -smtsolver yices \
-const bcmax=1,D=8,q=0.9
```

---

#### Expected outcome
Upon execution of each command:

- The model is parsed successfully
- (Robust) verification is performed
- A log containing various statistics, including: 1) the numerical value for the selected property, and 2) the verification time, is printed 

---

### General Command Pattern

All experiments in the paper follow this general pattern:

```
bin/prism <model>.prism <properties>.props \
-prop <property_number> -const <parameters>
```

---

### Notes on Reproducibility

- **Numerical results** (values, probabilities) are deterministic.
- **Execution time** may vary due to:
  - Hardware differences
  - JVM warm-up and solver initialisation (the first execution after a fresh build or JVM start typically takes the longest)
  - Available memory
- These variations do **not** affect correctness or conclusions.


