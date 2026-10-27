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
# BASELINE), runs them after, its reports prefixed base-. BB_RUNS picks the runs (a,c by default; d
# is the vendor on the ScalarDB tables, the layout's cost alone;
# b is the driver with every table plain), BB_JVM_ARGS the execute phase's JVM options ({dir}, the
# run's directory: -XX:StartFlightRecording:...,filename={dir}/execute.jfr), BB_LAYOUTS the layouts of
# run (c)'s tables (decoupled by default; "decoupled inline" runs both, inline's reports prefixed
# with -inline), BB_ISOLATIONS the driver's levels (REPEATABLE_READ by default; READ_COMMITTED's
# reports prefixed -rc, SERIALIZABLE's -ser; run (a) gets the vendor's matching level). NET_DELAYS,
# microseconds added to each round trip to the databases (net-delay.sh, sudo), runs BenchBase's whole
# set once per delay on the same instances, the reports prefixed -d<delay> (none by default: the
# network as it is). Order: delay, level, layout, tree, so a level's two layouts run back to back.
# PLAN, a plan file (bench-env/plan.py, bench-env/plans/), replaces the BB_* variables, REPS for
# BenchBase and NET_DELAYS: its workloads, levels, layouts and delays; a copy goes to results/.
set -uo pipefail
tier=$1; shift
tasks=(); wtasks=(); btasks=(); props=(); targets=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do
  # engine=url=user; a URL can hold '=', so the user is after the last one
  engine=${1%%=*}; user=${1##*=}; url=${1#*=}; url=${url%=*}
  task=$(python3 -c 'import sys; print(sys.argv[1].capitalize())' "$engine")
  tasks+=(":driver:integrationTest$task" --rerun --tests '*ReadOverheadBenchmark*')
  wtasks+=(":driver:integrationTest$task" --rerun --tests '*InFlightCheckBenchmark*')
  btasks+=(":driver:integrationTest$task" --rerun --tests '*BenchBaseBenchmark*')
  # host:port, for net-delay.sh
  targets+=("$(python3 -c 'import re, sys; print(re.search(r"(?://|@)([^:/;]+):(\d+)", sys.argv[1]).expand(r"\1:\2"))' "$url")")
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
# a plan is BenchBase's
[ -z "${PLAN:-}" ] || BENCHBASE=1
if [ -n "${BENCHBASE:-}" ]; then
  # BenchBase at the commit the harness was written against, built once per client
  bb=$HOME/benchbase-dist/benchbase-postgres
  if [ ! -e "$bb/benchbase.jar" ]; then
    echo "=== $tier building BenchBase $(date +%T)"
    rm -rf ~/benchbase-src ~/benchbase-dist
    git clone -q https://github.com/cmu-db/benchbase.git ~/benchbase-src &&
      git -C ~/benchbase-src checkout -q 33c00473807ebd49304d114a6d769d2d2b2bbb34 &&
      # its -Werror fails on JDK 25's new warnings; the classes are the same without it
      sed -i 's#<arg>-Werror</arg>##' ~/benchbase-src/pom.xml &&
      (cd ~/benchbase-src && ./mvnw -q -B clean package -P postgres -DskipTests -Dfmt.skip=true) &&
      mkdir -p ~/benchbase-dist && tar -xzf ~/benchbase-src/target/benchbase-postgres.tgz -C ~/benchbase-dist ||
      echo "=== $tier BenchBase build FAILED"
  fi
  results=$PWD/results
  # the cells, one a line (delay, the driver's level, layout, report prefix), and the properties
  # they share: from the plan (PLAN, bench-env/plan.py), else from the BB_* variables and NET_DELAYS
  cells=(); bbprops=()
  if [ -n "${PLAN:-}" ]; then
    python3 -I bench-env/plan.py "$PLAN" show | tee "$results/plan.txt" || exit 1
    # a baseline tree older than plans would take a workload's id for its benchmark
    if [ -n "${BASE_DIR:-}" ] && ! grep -q 'benchbase.benchmark.' \
        "$BASE_DIR/driver/src/integrationTest/java/com/scalar/db/nativejdbc/bench/BenchBaseBenchmark.java"; then
      echo "=== $tier PLAN with a baseline tree that predates plans FAILED"; exit 1
    fi
    cp "$PLAN" "$results/plan.json"
    while IFS= read -r line; do cells+=("$line"); done < <(python3 -I bench-env/plan.py "$PLAN" cells)
    while IFS= read -r line; do bbprops+=("$line"); done < <(python3 -I bench-env/plan.py "$PLAN" props)
  else
    for delay in ${NET_DELAYS:-none}; do for isolation in ${BB_ISOLATIONS:-REPEATABLE_READ}; do
      for layout in ${BB_LAYOUTS:-decoupled}; do
        prefix=bb; [ "$layout" = decoupled ] || prefix=$prefix-$layout
        case $isolation in READ_COMMITTED) prefix=$prefix-rc ;; SERIALIZABLE) prefix=$prefix-ser ;; esac
        [ "$delay" = none ] || prefix=$prefix-d$delay
        cells+=("$delay"$'\t'"$isolation"$'\t'"$layout"$'\t'"$prefix")
      done
    done; done
    bbprops=("-Dnativejdbc.benchbase.workloads=${BB_WORKLOADS:-tpcc,ycsb}"
      "-Dnativejdbc.benchbase.runs=${BB_RUNS:-a,c}" "-Dnativejdbc.benchbase.reps=${REPS:-3}"
      "-Dnativejdbc.benchbase.time=${BB_TIME:-120}" -Dnativejdbc.benchbase.scale.tpcc=4
      -Dnativejdbc.benchbase.weights.ycsb=50,5,15,10,0,20)
    [ -z "${BB_JVM_ARGS:-}" ] || bbprops+=("-Dnativejdbc.benchbase.jvmArgs=$BB_JVM_ARGS")
  fi
  # the client's delay as it stands: a plan's first cells at 0 need no change of it
  # set as soon as net-delay.sh is called, which may leave its delay on even when its check fails
  current=none; [ -z "${PLAN:-}" ] || current=0
  failed=" "
  for cell in "${cells[@]}"; do
  IFS=$'\t' read -r delay isolation layout cellprefix <<< "$cell"
  # a delay that failed its check: its other cells skipped, not tried again
  case $failed in *" $delay "*) continue ;; esac
  if [ "$delay" != none ] && [ "$delay" != "$current" ]; then
    echo "=== $tier network delay $delay µs $(date +%T)"
    current=$delay
    sudo bash bench-env/net-delay.sh "$delay" "${targets[@]}" 2>&1 | tee "results/net-delay-$delay.txt" ||
      { echo "=== $tier network delay $delay FAILED"; failed="$failed$delay "; continue; }
  fi
  for tree in . ${BASE_DIR:-}; do
    prefix=$cellprefix; [ "$tree" = . ] || prefix=base${cellprefix#bb}
    echo "=== $tier $prefix $(date +%T)"
    # this invocation's runs alone in the archive below
    (cd "$tree" || { echo "=== $tier $prefix: no tree $tree FAILED"; exit 1; }
    rm -rf driver/build/bench && ./gradlew "${btasks[@]}" "${props[@]}" -Dnativejdbc.bench=true \
      "-Dnativejdbc.benchbase=$bb" "${bbprops[@]}" \
      "-Dnativejdbc.benchbase.layout=$layout" "-Dnativejdbc.benchbase.isolation=$isolation" \
      "-Dnativejdbc.bench.netDelayUs=${delay/none/0}" -Dnativejdbc.benchbase.serverStats=true \
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
  done
  [ "$current" = none ] || [ "$current" = 0 ] || sudo bash bench-env/net-delay.sh 0 "${targets[@]}" > /dev/null
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
