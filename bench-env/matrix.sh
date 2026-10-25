#!/bin/bash
# Runs on the client, in one checkout per tier: the read benchmark against that tier's databases,
# all engines in parallel, for each in-flight count and run; the reports go to results/.
#
# Usage: matrix.sh TIER engine=url=user... -- [-Dproperty=value...]
# Environment: BENCH_DB_PASSWORD; INFLIGHT (default "100 10000"); REPS (default 3); CHANGED=1 adds
# a run per in-flight count with changed images, its rows checked against VIEW_LJ. WRITERS=1 adds
# InFlightCheckBenchmark's writers mode, REPS runs, one engine at a time so that the engines do not
# share the client's CPU (with -Dnativejdbc.bench.checkPlan=true, Oracle's plan of the predicate's
# check instead); READS=0 leaves out the read benchmark. BENCHBASE=1 adds BenchBase's workloads
# (BenchBaseBenchmark, milestone T6), one engine at a time; BASE_DIR, a second tree (run.sh's
# BASELINE), runs them after, its reports prefixed base-.
set -uo pipefail
tier=$1; shift
tasks=(); wtasks=(); btasks=(); props=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do
  # engine=url=user; a URL can hold '=', so the user is after the last one
  engine=${1%%=*}; user=${1##*=}; url=${1#*=}; url=${url%=*}
  task=$(python3 -c 'import sys; print(sys.argv[1].capitalize())' "$engine")
  tasks+=(":driver:integrationTest$task" --rerun --tests '*ReadOverheadBenchmark*')
  wtasks+=(":driver:integrationTest$task" --rerun --tests '*InFlightCheckBenchmark*')
  btasks+=(":driver:integrationTest$task" --rerun --tests '*BenchBaseBenchmark*')
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
if [ -n "${BENCHBASE:-}" ]; then
  # BenchBase at the commit the harness was written against, built once per client
  bb=$HOME/benchbase-dist/benchbase-postgres
  if [ ! -e "$bb/benchbase.jar" ]; then
    echo "=== $tier building BenchBase $(date +%T)"
    rm -rf ~/benchbase-src ~/benchbase-dist
    git clone -q https://github.com/cmu-db/benchbase.git ~/benchbase-src &&
      git -C ~/benchbase-src checkout -q 33c00473807ebd49304d114a6d769d2d2b2bbb34 &&
      (cd ~/benchbase-src && ./mvnw -q -B clean package -P postgres -DskipTests -Dfmt.skip=true) &&
      mkdir -p ~/benchbase-dist && tar -xzf ~/benchbase-src/target/benchbase-postgres.tgz -C ~/benchbase-dist ||
      echo "=== $tier BenchBase build FAILED"
  fi
  results=$PWD/results
  for tree in . ${BASE_DIR:-}; do
    prefix=bb; [ "$tree" = . ] || prefix=base
    echo "=== $tier $prefix $(date +%T)"
    (cd "$tree" && ./gradlew "${btasks[@]}" "${props[@]}" -Dnativejdbc.bench=true \
      "-Dnativejdbc.benchbase=$bb" "-Dnativejdbc.benchbase.workloads=${BB_WORKLOADS:-tpcc,ycsb}" \
      -Dnativejdbc.benchbase.runs=a,c "-Dnativejdbc.benchbase.reps=${REPS:-3}" \
      "-Dnativejdbc.benchbase.time=${BB_TIME:-120}" -Dnativejdbc.benchbase.scale.tpcc=4 \
      -Dnativejdbc.benchbase.weights.ycsb=50,5,15,10,0,20 -Dnativejdbc.benchbase.serverStats=true \
      "-Dnativejdbc.bench.tier=$tier" --continue -Pnativejdbc.parallelEngines=1 "$@" 2>&1 | grep -E 'BUILD|FAILED'
    for f in driver/build/bench/benchbase-*.md; do
      if [ -e "$f" ]; then cp "$f" "$results/$prefix-$(basename "$f")"; fi
    done
    # each run's BenchBase output and server.txt, without the raw samples
    tar -czf "$results/$prefix-benchbase-runs.tgz" --exclude='*.raw.csv' -C driver/build/bench . 2>/dev/null
    # the tree's failed tests, as failures() keeps them, into this tier's results
    for f in $(grep -l '<failure' driver/build/test-results/*/*.xml 2>/dev/null); do
      cp "$f" "$results/$prefix-$(basename "$f")"
    done)
  done
fi
if [ -n "${WRITERS:-}" ]; then for rep in $(seq 1 "${REPS:-3}"); do
  echo "=== $tier writers-$rep $(date +%T)"
  ./gradlew "${wtasks[@]}" "${props[@]}" -Dnativejdbc.bench=true -Dnativejdbc.bench.writers=true \
    -Dnativejdbc.bench.dropExisting=true "-Dnativejdbc.bench.tier=$tier" "-Dnativejdbc.bench.rep=$rep" \
    --continue -Pnativejdbc.parallelEngines=1 "$@" 2>&1 | grep -E 'BUILD|FAILED'
  for f in driver/build/bench/inflight-writers-*.md driver/build/bench/inflight-plan-*.txt \
    driver/build/bench/inflight-arms-*.txt driver/build/bench/inflight-coldplan-*.txt \
    driver/build/bench/inflight-breakdown-*.txt driver/build/bench/plans; do
    if [ -e "$f" ]; then mv "$f" "results/writers-$rep-$(basename "$f")"; fi
  done
  failures "writers-$rep"
done; fi
