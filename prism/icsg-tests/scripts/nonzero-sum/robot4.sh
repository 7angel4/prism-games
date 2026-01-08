PROP_NO=7
CASE_STUDY="robot4"
L_VALS=(4 8 12)

RESULTS_FILE="icsg-tests/results/nonzero-sum/$CASE_STUDY.csv"
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
  echo "\"l,k\",Actions_max/avg,RNE_exists" >> "$RESULTS_FILE"

  extract_results() {
    local L="$1"

    while true; do
      OUTPUT=$(
        bin/prism \
          "$PRISM_FILE" \
          "$PROP_FILE" \
          -prop $PROP_NO -smtsolver yices \
          -const "$CONSTS",l="${L}" \
        | tee "${LOG_FILE}_${L}"
      )

      # Extract values
      MAX_AVG_ACTIONS=$(echo "$OUTPUT" \
        | grep 'Max/avg (actions)' \
        | sed -E 's/^.*Max\/avg \(actions\): //' \
        | tr ';' '\n' \
        | sed -E 's/^\(([^)]*)\)\/\(([^)]*)\)$/\1\/\2/' \
        | awk '{split($0,a,/[,\/]/); printf "%s,%s/%.2f,%.2f\n",a[1],a[2],a[3],a[4]}' \
        | head -1)

      if echo "$OUTPUT" | grep -q "Error: No Robust Nash equilibrium found"; then
        RNE_EXISTS=false
      else
        RNE_EXISTS=true
      fi

      if [[ -n "$MAX_AVG_ACTIONS" && -n "$RNE_EXISTS" ]]; then
        break
      else
        sleep 1
      fi
    done

    echo "$L,\"$MAX_AVG_ACTIONS\",$RNE_EXISTS" >> "$RESULTS_FILE"
  }

  for L in "${L_VALS[@]}"; do
    extract_results "$L"
  done

  # Add a blank line after the section
  echo "" >> "$RESULTS_FILE"
}

# Run ICSG section
run_experiments \
  "ICSG" \
  "icsg-tests/logs/nonzero-sum/icsgs/$CASE_STUDY" \
  "../prism-examples/csgs/robot_coordination/robot_coordination2_icsg.prism" \
  "../prism-examples/csgs/robot_coordination/robot_coordination2.props" \
  "q=0.25,k=0,eps=0.01"
