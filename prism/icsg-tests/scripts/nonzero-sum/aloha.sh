
D=0
PROP_NO=8
CASE_STUDY="aloha"
BMAX_VALS=(2 3)

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
  echo "bmax,Actions_max/avg,Val_iters,Verif_time,Value" >> "$RESULTS_FILE"

  extract_results() {
    local BMAX="$1"

    while true; do
      OUTPUT=$(
        bin/prism \
          "$PRISM_FILE" \
          "$PROP_FILE" \
          -prop $PROP_NO -smtsolver yices \
          -const "$CONSTS",bcmax="${BMAX}" \
        | tee "${LOG_FILE}_${BMAX}"
      )

      # Extract values
      MAX_AVG_ACTIONS=$(echo "$OUTPUT" \
        | grep 'Max/avg (actions)' \
        | sed -E 's/^.*Max\/avg \(actions\): //' \
        | tr ';' '\n' \
        | sed -E 's/^\(([^)]*)\)\/\(([^)]*)\)$/\1\/\2/' \
        | awk '{split($0,a,/[,\/]/); printf "%s,%s/%.5f,%.5f\n",a[1],a[2],a[3],a[4]}' \
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
    
    echo "$BMAX,\"$MAX_AVG_ACTIONS\",$VAL_ITERS,$TIME,$VALUE" >> "$RESULTS_FILE"
  }

  for BMAX in "${BMAX_VALS[@]}"; do
    extract_results "$BMAX"
  done

  # Add a blank line after the section
  echo "" >> "$RESULTS_FILE"
}

# Run ICSG section
run_experiments \
  "ICSG" \
  "icsg-tests/logs/nonzero-sum/icsgs/$CASE_STUDY" \
  "../prism-examples/csgs/aloha/aloha_backoff3_icsg.prism" \
  "../prism-examples/csgs/aloha/aloha_backoff3.props" \
  "D=$D,q=0.9,eps=1/257"

# Run CSG section
run_experiments \
  "CSG" \
  "icsg-tests/logs/nonzero-sum/csgs/$CASE_STUDY" \
  "../prism-examples/csgs/aloha/aloha_backoff3.prism" \
  "../prism-examples/csgs/aloha/aloha_backoff3.props" \
  "D=$D,q=0.9"
