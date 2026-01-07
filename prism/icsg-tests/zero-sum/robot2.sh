RESULTS_FILE="results/zero-sum/robot2.csv"
rm -f "$RESULTS_FILE"

run_experiments() {
  local SECTION_NAME="$1"
  local LOG_FILE="$2"
  local PRISM_FILE="$3"
  local PROP_FILE="$4"
  local CONSTS="$5"

  # Write section heading
  echo "=== $SECTION_NAME ===" >> "$RESULTS_FILE"
  # Write CSV header
  echo "l,Actions_max/avg,Result,Val_iters,Qual_verif_time,Quant_verif_time,Value" >> "$RESULTS_FILE"

  extract_results() {
    local L="$1"

    OUTPUT=$(
      bin/prism \
        "$PRISM_FILE" \
        "$PROP_FILE" \
        -prop 3 -const "$CONSTS",l="${L}" \
      | tee "${LOG_FILE}_${L}"
    )

    # Extract values
    MAX_AVG_ACTIONS=$(echo "$OUTPUT" \
      | grep 'Max/avg (actions)' \
      | sed -E 's/^.*Max\/avg \(actions\): //' \
      | tr ';' '\n' \
      | sed -E 's/^\(([^)]*)\)\/\(([^)]*)\)$/\1\/\2/' \
      | awk '!seen[$0]++ {split($0,a,/[,\/]/); printf "%s,%s/%.2f,%.2f\n",a[1],a[2],a[3],a[4]}')

    VALUE=$(echo "$OUTPUT" | grep 'Result:' | sed -E 's/.*Result: ([0-9eE\.\+\-]+).*/\1/' | head -1 | xargs printf "%.2f")
    VAL_ITERS=$(echo "$OUTPUT" | grep 'Value iteration converged after' | head -2 | sed -E 's/.*after ([0-9]+).*/\1/' | tr '\n' ';' | sed 's/;$//')
    QUANT_TIME=$(echo "$OUTPUT" | grep 'Expected reachability took' | head -1 | sed -E 's/.*took ([0-9\.]+) seconds.*/\1/' | xargs printf "%.2f")
    QUAL_TIME=$(echo "$OUTPUT" | grep 'Precomputation took' | head -1 | sed -E 's/.*Precomputation took ([0-9\.]+).*/\1/' | xargs printf "%.2f")

    if [[ -n "$MAX_AVG_ACTIONS" && -n "$VALUE" && -n "$VAL_ITERS" && -n "$QUANT_TIME" && -n "$QUAL_TIME" ]]; then
      echo "$L,\"$MAX_AVG_ACTIONS\",$VAL_ITERS,$QUAL_TIME,$QUANT_TIME,$VALUE" >> "$RESULTS_FILE"
    fi
  }

  for L in 4 8 12; do
    extract_results "$L"
  done

  # Add a blank line after the section
  echo "" >> "$RESULTS_FILE"
}

# Run ICSG section
run_experiments \
  "ICSG" \
  "logs/zero-sum/icsgs/robot2" \
  "../prism-examples/csgs/robot_coordination/robot_coordination2_icsg.prism" \
  "../prism-examples/csgs/robot_coordination/robot_coordination2.props" \
  "k=0,q=0.1,eps=0.01"

# Run CSG section
run_experiments \
  "CSG" \
  "logs/zero-sum/csgs/robot2" \
  "../prism-examples/csgs/robot_coordination/robot_coordination2.prism" \
  "../prism-examples/csgs/robot_coordination/robot_coordination2.props" \
  "q=0.1"
