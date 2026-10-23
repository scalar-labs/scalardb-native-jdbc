#!/bin/bash
# Runs on the client, in one checkout per tier: the read benchmark against that tier's databases,
# all engines in parallel, for each in-flight count and run; the reports go to results/.
#
# Usage: matrix.sh TIER engine=url=user... -- [-Dproperty=value...]
# Environment: BENCH_DB_PASSWORD; INFLIGHT (default "100 10000"); REPS (default 3); CHANGED=1 adds
# a run per in-flight count with changed images, its rows checked against VIEW_LJ. WRITERS=1 adds
# InFlightCheckBenchmark's writers mode, REPS runs, one engine at a time so that the engines do not
# share the client's CPU (with -Dnativejdbc.bench.checkPlan=true, Oracle's plan of the predicate's
# check instead); READS=0 leaves out the read benchmark.
set -uo pipefail
tier=$1; shift
tasks=(); wtasks=(); props=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do
  # engine=url=user; a URL can hold '=', so the user is after the last one
  engine=${1%%=*}; user=${1##*=}; url=${1#*=}; url=${url%=*}
  task=$(python3 -c 'import sys; print(sys.argv[1].capitalize())' "$engine")
  tasks+=(":driver:integrationTest$task" --rerun --tests '*ReadOverheadBenchmark*')
  wtasks+=(":driver:integrationTest$task" --rerun --tests '*InFlightCheckBenchmark*')
  props+=("-Dnativejdbc.$engine.url=$url" "-Dnativejdbc.$engine.username=$user"
    "-Dnativejdbc.$engine.password=$BENCH_DB_PASSWORD")
  shift
done
shift || true
mkdir -p results
# a failed test's report, its stack trace and output, kept with the run's reports as PREFIX-<file>
failures() {
  for f in $(grep -l '<failure' driver/build/test-results/*/*.xml 2>/dev/null); do
    cp "$f" "results/$1-$(basename "$f")"
  done
}
[ "${READS:-1}" = 0 ] || for n in ${INFLIGHT:-100 10000}; do
  for rep in $(seq 1 "${REPS:-3}") ${CHANGED:+changed}; do
    changed=(); [ "$rep" = changed ] && changed=(-Dnativejdbc.bench.changed=true)
    echo "=== $tier i$n-$rep $(date +%T)"
    ./gradlew "${tasks[@]}" "${props[@]}" -Dnativejdbc.bench=true -Dnativejdbc.bench.dropExisting=true \
      "-Dnativejdbc.bench.tier=$tier" "-Dnativejdbc.bench.inflight=$n" "-Dnativejdbc.bench.rep=$rep" \
      --continue "-Pnativejdbc.parallelEngines=$((${#props[@]} / 3))" "${changed[@]}" "$@" 2>&1 | grep -E 'BUILD|FAILED'
    for f in driver/build/bench/read-overhead-*.md driver/build/bench/explain-*.txt; do
      if [ -e "$f" ]; then mv "$f" "results/i$n-$rep-$(basename "$f")"; fi
    done
    failures "i$n-$rep"
  done
done
if [ -n "${WRITERS:-}" ]; then for rep in $(seq 1 "${REPS:-3}"); do
  echo "=== $tier writers-$rep $(date +%T)"
  ./gradlew "${wtasks[@]}" "${props[@]}" -Dnativejdbc.bench=true -Dnativejdbc.bench.writers=true \
    -Dnativejdbc.bench.dropExisting=true "-Dnativejdbc.bench.tier=$tier" \
    --continue -Pnativejdbc.parallelEngines=1 "$@" 2>&1 | grep -E 'BUILD|FAILED'
  for f in driver/build/bench/inflight-writers-*.md driver/build/bench/inflight-plan-*.txt \
    driver/build/bench/inflight-arms-*.txt driver/build/bench/inflight-coldplan-*.txt \
    driver/build/bench/inflight-breakdown-*.txt; do
    if [ -e "$f" ]; then mv "$f" "results/writers-$rep-$(basename "$f")"; fi
  done
  failures "writers-$rep"
done; fi
