# Artifact for "Robust Verification of Concurrent Stochastic Games"

This artifact accompanies the TACAS 2026 paper:

> **Robust Verification of Concurrent Stochastic Games**. Angel He and David Parker

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
- **macOS** or **Linux**, on either **Intel** or **Arm** hardware

### Software
- [for building] **Java** (version 9 or above), GNU **make** and a C/C++ compiler (e.g. **gcc**/**g++**)

### Hardware
- Recommended RAM: **≥ 16 GB**
- Minimum RAM for smoke test: **8 GB**

---

## Build Instructions

This assumes that you have unzipped this archive and entered this directory
(e.g., `unzip prism-games-tacas-26.zip && cd prism-games-tacas-26`)

1. Build PRISM-games:
```
cd prism
make
```
2. (Recommended) Configure Java heap size:
```
export PRISM_JAVA_ARGS="-Xms4G -Xmx16G"
```

3. Test the PRISM-games executable:
```
bin/prism
```

---

## Early Smoke Test (Example Commands)

- Assumes that you have compiled as above, and are in the `prism` subdirectory
- **Expected time:** 1–2 minutes  

### Zero-sum ICSG vs CSG

**Case study:** Jamming Radio Systems (Property 1)

ICSG:
```
bin/prism ../prism-examples/csgs/jamming/jamming4_icsg.prism ../prism-examples/csgs/jamming/jamming.props -prop 1 -const chans=4,slots=6,eps=0.01
```

CSG:
```
bin/prism ../prism-examples/csgs/jamming/jamming4.prism ../prism-examples/csgs/jamming/jamming.props -prop 1 -const chans=4,slots=6
```

---

### Nonzero-sum ICSG vs CSG

**Case study:** Aloha (Deadline)

ICSG:
```
bin/prism ../prism-examples/csgs/aloha/aloha_backoff3_icsg.prism ../prism-examples/csgs/aloha/aloha_backoff3.props -prop 7 -smtsolver yices -const bcmax=1,D=8,q=0.9,eps=1/257
```

CSG:
```
bin/prism ../prism-examples/csgs/aloha/aloha_backoff3.prism ../prism-examples/csgs/aloha/aloha_backoff3.props -prop 7 -smtsolver yices -const bcmax=1,D=8,q=0.9
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


#### Output File Naming Conventions

Test result files follow the format:
```
<case_study_name>.csv
```

Test log files follow the format:

```
<case_study_name>_<param_vals>
```

* **`case_study_name`** matches the name of the script that generated the output.
* **`param_vals`** lists the parameter value(s) tested in that run (separated by `_` for different parameters).
  - For file naming, uncertainty parameter $\epsilon$ is encoded as integers by multiplying by 100 or 1000, depending on precision, e.g. `{0.01, 0.02, 0.025} → {100, 200, 25}`.


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

⚠️ **Note:** Running all experiments can take a **very long time** (hours to days, depending on hardware) and may trigger **Java out-of-memory** errors on machines with limited RAM. 

For artifact evaluation, we strongly recommend running the provided
smoke tests or **individual case-study scripts**, rather than the full experiment suite.

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

Each case study evaluates multiple parameter values, which can be time-consuming and may cause memory issues on some machines. For this reason, the provided scripts restrict parameter values to configurations that typically complete within **< 1 hour**.

To run the **full set of parameter values**, modify the constants at the top of the corresponding script.

**Example:** In `scripts/zero-sum/aloha.sh`, comment out:
```
BMAX_VALS=(2 3 4)
```
and uncomment:
```
BMAX_VALS=(2 3 4 5)
```
to evaluate the complete parameter set. Note that the configuration `bmax=5` can take up to ~1.5 hours to complete.

---

## Notes on Reproducibility

- **Numerical results** (values and probabilities) are deterministic, up to minor
  floating-point rounding differences.
- **Execution time** may vary due to:
  - hardware differences,
  - JVM warm-up and solver initialisation (the first run is typically the slowest),
  - available memory.
- These variations do **not** affect correctness or the conclusions of the paper.