PROP_NO=5
CASE_STUDY="robot2"
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
  echo "\"l,k1,k2\",Actions_max/avg,Val_iters,Verif_time,Value" >> "$RESULTS_FILE"

  extract_results() {
    local L="$1"
    local K1="$2"
    local K2="$3"

    while true; do
      OUTPUT=$(
        bin/prism \
          "$PRISM_FILE" \
          "$PROP_FILE" \
          -prop $PROP_NO -smtsolver yices \
          -const "$CONSTS",l="${L}",k1="${K1}",k2="${K2}" \
        | tee "${LOG_FILE}_${L}_${K1}_${K2}"
      )

      # Extract values
      MAX_AVG_ACTIONS=$(echo "$OUTPUT" \
        | grep 'Max/avg (actions)' \
        | sed -E 's/^.*Max\/avg \(actions\): //' \
        | tr ';' '\n' \
        | sed -E 's/^\(([^)]*)\)\/\(([^)]*)\)$/\1\/\2/' \
        | awk '{split($0,a,/[,\/]/); printf "%s,%s/%.2f,%.2f\n",a[1],a[2],a[3],a[4]}' \
        | head -1)

      VALUE=$(echo "$OUTPUT" | grep 'Result:' | sed -E 's/.*Result: ([0-9eE\.\+\-]+).*/\1/' | head -1 | xargs printf "%.2f")
      VAL_ITERS=$K2
      TIME=$(echo "$OUTPUT" | grep 'Time for model checking:' | head -1 | sed -E 's/.*Time for model checking: ([0-9\.]+).*/\1/' | xargs printf "%.2f")

      if [[ -n "$MAX_AVG_ACTIONS" && -n "$VALUE" && -n "$VAL_ITERS" && -n "$TIME" ]]; then
        break
      else
        sleep 1
      fi
    done

    echo "\"$L,$K1,$K2\",\"$MAX_AVG_ACTIONS\",$VAL_ITERS,$TIME,$VALUE" >> "$RESULTS_FILE"
  }

  for L in "${L_VALS[@]}"; do
    extract_results "$L" "$L" "$((L+2))"  # K1=L, K2=K1+2
  done

  # Add a blank line after the section
  echo "" >> "$RESULTS_FILE"
}

# Run ICSG section
run_experiments \
  "ICSG" \
  "icsg-tests/logs/nonzero-sum/icsgs/$CASE_STUDY" \
  "../prism-examples/csgs/robot_coordination/robot_coordination2_icsg.prism" \
  "../prism-examples/csgs/robot_coordination/robot_coordination.props" \
  "q=0.1,eps=0.01"

# Run CSG section
run_experiments \
  "CSG" \
  "icsg-tests/logs/nonzero-sum/csgs/$CASE_STUDY" \
  "../prism-examples/csgs/robot_coordination/robot_coordination2.prism" \
  "../prism-examples/csgs/robot_coordination/robot_coordination.props" \
  "q=0.1"
