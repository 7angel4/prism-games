PROP_NO=3
CASE_STUDY="user"
K_VALS=(3) 
# K_VALS=(3 4)  # full parameter set 

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
  echo "K,Actions_max/avg,Val_iters,Verif_time,Value" >> "$RESULTS_FILE"

  extract_results() {
    local K="$1"

    while true; do
      OUTPUT=$(
        bin/prism \
          "$PRISM_FILE" \
          "$PROP_FILE" \
          -prop $PROP_NO -const "$CONSTS",K="${K}" \
        | tee "${LOG_FILE}_${K}"
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

      # Break loop only if all fields are non-empty
      if [[ -n "$MAX_AVG_ACTIONS" && -n "$VALUE" && -n "$VAL_ITERS" && -n "$TIME" ]]; then
        break
      else
        sleep 1
      fi
    done

    echo "$K,\"$MAX_AVG_ACTIONS\",$VAL_ITERS,$TIME,$VALUE" >> "$RESULTS_FILE"
  }

  for K in "${K_VALS[@]}"; do
    extract_results "$K"
  done

  # Add a blank line after the section
  echo "" >> "$RESULTS_FILE"
}

# Run ICSG section
run_experiments \
  "ICSG" \
  "icsg-tests/logs/zero-sum/icsgs/$CASE_STUDY" \
  "../prism-examples/csgs/user-centric/user-centric-icsg.prism" \
  "../prism-examples/csgs/user-centric/user-centric.props" \
  "td=1,eps=0.01"

# Run CSG section
run_experiments \
  "CSG" \
  "icsg-tests/logs/zero-sum/csgs/$CASE_STUDY" \
  "../prism-examples/csgs/user-centric/user-centric.prism" \
  "../prism-examples/csgs/user-centric/user-centric.props" \
  "td=1"
