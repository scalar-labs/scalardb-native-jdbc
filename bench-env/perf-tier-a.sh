#!/bin/bash
# Profiles tier A's PostgreSQL with perf while matrix.sh's BENCHBASE=1 runs (todo 329), from the
# laptop, during a run of run.sh with profiling = true in its tfvars: for each BenchBase run named,
# once it measures, 45 s of perf with call stacks on the database host, which only the client
# reaches, then its reports, made there at the lowest priority, copied to OUT.
#
# Usage: perf-tier-a.sh OUT RUN... (as tpcc-a-1 tpcc-c-1, BenchBaseBenchmark's run directories)
# Environment: SSH_KEY, as for run.sh.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:?usage: perf-tier-a.sh OUT RUN...}
shift
mkdir -p "$out"
client=$(terraform -chdir="$here/terraform" output -raw client)
db=$(terraform -chdir="$here/terraform" output -json urls | python3 -c '
import json, re, sys
print(re.search(r"//([^:/]+)", json.load(sys.stdin)["a-postgresql"]).group(1))')
opts=(-i "${SSH_KEY:?}" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o ServerAliveInterval=30)
on_client() { ssh "${opts[@]}" "ubuntu@$client" "$@"; }
on_db() { ssh "${opts[@]}" -o "ProxyCommand=ssh ${opts[*]} -W %h:%p ubuntu@$client" "ubuntu@$db" "$@"; }

until on_db 'command -v perf > /dev/null && perf --version' 2> /dev/null; do sleep 15; done
for run in "$@"; do
  dir="a/driver/build/bench/benchbase-postgresql/$run"
  echo "=== $run: waiting $(date +%T)"
  # measuring: BenchBase's warmup is over and the run has no summary yet
  until [ "$(on_client "grep -c 'starting measurements' $dir/execute.log 2> /dev/null; ls $dir/execute 2> /dev/null | grep -c summary.json" 2> /dev/null | tr '\n' ' ')" = "1 0 " ]; do
    sleep 10
  done
  sleep 10
  echo "=== $run: recording $(date +%T)"
  on_db "sudo perf record -F 49 -a --call-graph dwarf,16384 -o /tmp/$run.data -- sleep 45 2> /dev/null"
  echo "=== $run: reporting $(date +%T)"
  # the backends' own time by function and library, and each function with what it calls
  on_db "cd /tmp && sudo nice -n 19 perf report -i $run.data --comm postgres --no-children \
      --sort dso,symbol --stdio -g none --percent-limit 0.3 > $run.self.txt 2> /dev/null; \
    sudo nice -n 19 perf report -i $run.data --comm postgres --children --sort symbol --stdio \
      -g none --percent-limit 0.5 > $run.children.txt 2> /dev/null"
  for f in self children; do
    on_db "cat /tmp/$run.$f.txt" > "$out/perf-$run.$f.txt"
  done
  echo "=== $run: $(wc -l < "$out/perf-$run.children.txt") lines in $out/perf-$run.children.txt"
done
