PROP_NO=1
CASE_STUDY="intrusion"
ROUNDS_VALS=(25 50 100 200)
EPS_VALS=(0.01 0.05 0.10 0.19)

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
  HEADER="rounds,Actions_max/avg,Val_iters,Verif_time,Value"
  if [[ "$SECTION_NAME" == "ICSG" ]]; then
    HEADER="eps,$HEADER"
  fi
  echo "$HEADER" >> "$RESULTS_FILE"

  extract_results() {
    local EPS="$1"
    local ROUNDS="$2"

    CONST_STRING="$CONSTS,rounds=${ROUNDS}"
    LOG_FILENAME="${LOG_FILE}_${ROUNDS}"
    # Add eps only for ICSG
    if [[ "$SECTION_NAME" == "ICSG" ]]; then
      CONST_STRING+=",eps=${EPS}"
      # Multiply EPS by 100 and remove decimals for filename
      EPS_INT=$(printf "%.0f" "$(echo "$EPS * 100" | bc -l)")
      LOG_FILENAME+="_${EPS_INT}"
    fi

    while true; do
      OUTPUT=$(
        bin/prism \
          "$PRISM_FILE" \
          "$PROP_FILE" \
          -prop $PROP_NO -const "$CONST_STRING" \
        | tee "$LOG_FILENAME"
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
    
    LINE="$ROUNDS,\"$MAX_AVG_ACTIONS\",$VAL_ITERS,$TIME,$VALUE"
    if [[ "$SECTION_NAME" == "ICSG" ]]; then
      LINE="$EPS,$LINE"
    fi
    echo "$LINE" >> "$RESULTS_FILE"
  }

  if [[ "$SECTION_NAME" == "ICSG" ]]; then
    for EPS in "${EPS_VALS[@]}"; do
      for ROUNDS in "${ROUNDS_VALS[@]}"; do
        extract_results "$EPS" "$ROUNDS"
      done
    done
  else
    for ROUNDS in "${ROUNDS_VALS[@]}"; do
      extract_results "" "$ROUNDS"
    done
  fi

  # Add a blank line after the section
  echo "" >> "$RESULTS_FILE"
}

# Run ICSG section
run_experiments \
  "ICSG" \
  "icsg-tests/logs/zero-sum/icsgs/$CASE_STUDY" \
  "../prism-examples/csgs/ids/ids_scenarios_icsg.prism" \
  "../prism-examples/csgs/ids/ids.props" \
  "scenario=1,K=1"

# Run CSG section
run_experiments \
  "CSG" \
  "icsg-tests/logs/zero-sum/csgs/$CASE_STUDY" \
  "../prism-examples/csgs/ids/ids_scenarios.prism" \
  "../prism-examples/csgs/ids/ids.props" \
  "scenario=1,K=1"
