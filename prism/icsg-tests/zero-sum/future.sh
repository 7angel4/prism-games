PROP_NO=1
CASE_STUDY="future"
MONTHS_VALS=(6 12 24 36 48)

RESULTS_FILE="results/zero-sum/$CASE_STUDY.csv"
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
  echo "months,Actions_max/avg,Val_iters,Qual_verif_time,Quant_verif_time,Value" >> "$RESULTS_FILE"
  
  extract_results() {
    local MONTHS="$1"

    while true; do
      OUTPUT=$(
        bin/prism \
          "$PRISM_FILE" \
          "$PROP_FILE" \
          -prop $PROP_NO -const "$CONSTS"months="${MONTHS}" \
        | tee "${LOG_FILE}_${MONTHS}"
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
      EXP_TOT_REW=$(echo "$OUTPUT" | grep 'Expected total reward took' | sed -E 's/.*took ([0-9\.]+) seconds.*/\1/' | head -1)
      QUAL_TIME=$(echo "$OUTPUT" | grep 'Precomputation took' | head -1 | sed -E 's/.*Precomputation took ([0-9\.]+).*/\1/')
      QUANT_TIME=$(echo "$EXP_TOT_REW $QUAL_TIME" | tr -d '[:space:]' | awk -F'-' '{print $1-$2}' | xargs printf "%.2f")
      QUAL_TIME=$(printf "%.2f" "$QUAL_TIME")

      if [[ -n "$MAX_AVG_ACTIONS" && -n "$VALUE" && -n "$VAL_ITERS" && -n "$QUANT_TIME" && -n "$QUAL_TIME" ]]; then
        break
      else
        sleep 1
      fi
    done

    echo "$MONTHS,\"$MAX_AVG_ACTIONS\",$VAL_ITERS,$QUAL_TIME,$QUANT_TIME,$VALUE" >> "$RESULTS_FILE"
  }

  for MONTHS in "${MONTHS_VALS[@]}"; do
    extract_results "$MONTHS"
  done

  # Add a blank line after the section
  echo "" >> "$RESULTS_FILE"
}

# Run ICSG section
run_experiments \
  "ICSG" \
  "logs/zero-sum/icsgs/$CASE_STUDY" \
  "../prism-examples/csgs/investors/two_investors_icsg.prism" \
  "../prism-examples/csgs/investors/two_investors.props" \
  "eps=0.01,"

# Run CSG section
run_experiments \
  "CSG" \
  "logs/zero-sum/csgs/$CASE_STUDY" \
  "../prism-examples/csgs/investors/two_investors_csg.prism" \
  "../prism-examples/csgs/investors/two_investors.props" \
  ""
