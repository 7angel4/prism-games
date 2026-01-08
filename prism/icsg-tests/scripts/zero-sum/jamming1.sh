
PROP_NO=1
CASE_STUDY="jamming1"
CHANS_VALS=(4 6)
SLOTS_VALS=(6 12)

RESULTS_FILE="icsg-tests/results/zero-sum/$CASE_STUDY.csv"
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
  echo "\"chans,slots\",Actions_max/avg,Val_iters,Verif_time,Value" >> "$RESULTS_FILE"

  extract_results() {
    local CHANS="$1"
    local SLOTS="$2"

    while true; do
      OUTPUT=$(
        bin/prism \
          "$PRISM_FILE" \
          "$PROP_FILE" \
          -prop $PROP_NO -const "$CONSTS"chans="$CHANS",slots="$SLOTS" \
        | tee "${LOG_FILE}_${CHANS}_${SLOTS}"
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
      VAL_ITERS=$(echo "$OUTPUT" | grep 'Value iteration converged after' | sed -E 's/.*after ([0-9]+).*/\1/' | head -1)
      TIME=$(echo "$OUTPUT" | grep 'Time for model checking:' | head -1 | sed -E 's/.*Time for model checking: ([0-9\.]+).*/\1/' | xargs printf "%.2f")

      if [[ -n "$MAX_AVG_ACTIONS" && -n "$VALUE" && -n "$VAL_ITERS" && -n "$TIME" ]]; then
        break
      else
        sleep 1
      fi
    done
    
    echo "\"$CHANS,$SLOTS\",\"$MAX_AVG_ACTIONS\",$VAL_ITERS,$TIME,$VALUE" >> "$RESULTS_FILE"
  }

  for CHANS in "${CHANS_VALS[@]}"; do
    for SLOTS in "${SLOTS_VALS[@]}"; do
      extract_results "$CHANS" "$SLOTS"
    done
  done

  # Add a blank line after the section
  echo "" >> "$RESULTS_FILE"
}

# Run ICSG section
run_experiments \
  "ICSG" \
  "icsg-tests/logs/zero-sum/icsgs/$CASE_STUDY" \
  ../prism-examples/csgs/jamming/jamming4_icsg.prism \
  ../prism-examples/csgs/jamming/jamming.props \
  "eps=0.01,"

# Run CSG section
run_experiments \
  "CSG" \
  "icsg-tests/logs/zero-sum/csgs/$CASE_STUDY" \
  ../prism-examples/csgs/jamming/jamming4.prism \
  ../prism-examples/csgs/jamming/jamming.props \
  ""
