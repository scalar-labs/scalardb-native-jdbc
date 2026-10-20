#!/bin/bash
# Runs on the client, in one checkout per tier: the read benchmark against that tier's databases,
# all engines in parallel, for each in-flight count and run; the reports go to results/.
#
# Usage: matrix.sh TIER engine=url=user... -- [-Dproperty=value...]
# Environment: BENCH_DB_PASSWORD; INFLIGHT (default "100 10000"); REPS (default 3).
set -uo pipefail
tier=$1; shift
tasks=(); props=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do
  # engine=url=user; a URL can hold '=', so the user is after the last one
  engine=${1%%=*}; user=${1##*=}; url=${1#*=}; url=${url%=*}
  task=$(python3 -c 'import sys; print(sys.argv[1].capitalize())' "$engine")
  tasks+=(":driver:integrationTest$task" --rerun --tests '*ReadOverheadBenchmark*')
  props+=("-Dnativejdbc.$engine.url=$url" "-Dnativejdbc.$engine.username=$user"
    "-Dnativejdbc.$engine.password=$BENCH_DB_PASSWORD")
  shift
done
shift || true
mkdir -p results
for n in ${INFLIGHT:-100 10000}; do
  for rep in $(seq 1 "${REPS:-3}"); do
    echo "=== $tier i$n-$rep $(date +%T)"
    ./gradlew "${tasks[@]}" "${props[@]}" -Dnativejdbc.bench=true -Dnativejdbc.bench.dropExisting=true \
      "-Dnativejdbc.bench.tier=$tier" "-Dnativejdbc.bench.inflight=$n" "-Dnativejdbc.bench.rep=$rep" \
      --continue "-Pnativejdbc.parallelEngines=$((${#props[@]} / 3))" "$@" 2>&1 | grep -E 'BUILD|FAILED'
    for f in driver/build/bench/read-overhead-*.md driver/build/bench/explain-*.txt; do
      [ -e "$f" ] && mv "$f" "results/i$n-$rep-$(basename "$f")"
    done
  done
done
