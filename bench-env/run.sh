#!/bin/bash
# One benchmark session on AWS (docs/plans/benchmark-environment.md), with the short-lived
# credentials of `aws login` for the profile scalardb-bench.
#
#   run.sh plan   writes terraform/bench.tfplan and shows it, for the user to approve;
#   run.sh run    applies that plan, copies the working tree (without what git ignores) to the client once per tier,
#                 runs bench-env/matrix.sh per tier, copies the reports to bench-env/results/<time>/
#                 and destroys everything: at the end, after a failure or an interrupt, after
#                 MAX_RUN_MINUTES, and as soon as the credentials can no longer be refreshed (the
#                 login session ended), while the last ones still work.
#                 With BENCH_BUCKET (and bench.tfvars' client_profile) it instead starts detached.sh on
#                 the client, which runs the tiers, uploads the reports and ends the instances by
#                 itself, and returns: the laptop can close.
#   run.sh apply  applies terraform/bench.tfplan alone (a change while a run is going, such as the
#                 admin address).
#   run.sh fetch RUN   after a detached run: its reports from the bucket to bench-env/results/RUN/,
#                 then destroys what is left (the VPC and the rest, which cost nothing).
#
# Before either: aws login --profile hiroyuki_yamada (the user signs in). run.sh then works only as
# the role scalardb-bench (setup-account.sh), assumed anew on every refresh.
# Environment: BENCH_DB_PASSWORD, SSH_KEY (the private key of bench.tfvars' public one), BENCH_BUCKET,
# MAX_RUN_MINUTES (default 480, below a login session of 12 hours, with room for the destroy);
# matrix.sh's REPS, INFLIGHT, CHANGED, WRITERS, READS, BENCHBASE, BB_WORKLOADS, BB_TIME, BB_RUNS,
# BB_JVM_ARGS and BB_LAYOUTS; BASELINE,
# a commit whose tree BENCHBASE runs too; NET_DELAY_US (default 0), a delay added to each round trip
# from the client to the databases (net-delay.sh), for a slower network than the placement gives.
# Arguments after `run` go to matrix.sh (and from there to Gradle).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(dirname "$here")
login=${AWS_LOGIN_PROFILE:-hiroyuki_yamada} # the user's `aws login` profile
creds=$(mktemp -d)/credentials # the exported credentials, refreshed by the heartbeat
chmod 700 "$(dirname "$creds")"

# the role's credentials, as the environment Terraform reads, for an hour
refresh() {
  (umask 077 && aws sts assume-role --profile "$login" --duration-seconds 3600 \
    --role-arn "arn:aws:iam::$(aws sts get-caller-identity --profile "$login" --query Account --output text):role/scalardb-bench" \
    --role-session-name "bench-$(date +%Y%m%d-%H%M%S)" --output text \
    --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' |
    awk '{print "export AWS_ACCESS_KEY_ID=" $1 "\nexport AWS_SECRET_ACCESS_KEY=" $2 "\nexport AWS_SESSION_TOKEN=" $3}' > "$creds.new") &&
    [ -s "$creds.new" ] && mv "$creds.new" "$creds"
}
tf() { (. "$creds" && terraform -chdir="$here/terraform" "$@"); }
recover() {
  cat >&2 <<EOF
DESTROY FAILED: resources may still be running and costing money. Sign in again and destroy:
  aws login --profile $login
  eval "\$(aws configure export-credentials --profile $login --format env)"
  TF_VAR_db_password=x terraform -chdir=$here/terraform destroy -var-file=${TFVARS:-bench.tfvars}
EOF
}

refresh || { echo "no credentials: aws login --profile $login first" >&2; exit 1; }
case "${1:-}" in
  plan)
    tf init -input=false >/dev/null
    # the plan file holds the variables, the password included: no placeholder (*.tfplan is ignored)
    TF_VAR_db_password=${BENCH_DB_PASSWORD:?} tf plan -input=false -var-file=${TFVARS:-bench.tfvars} -out=bench.tfplan
    exit ;;
  apply)
    # the plan written by `plan` alone, as for a security group's new address while `run` waits
    tf apply -input=false bench.tfplan
    exit ;;
  fetch)
    out="${RESULTS_DIR:-$here/results}/${2:?usage: run.sh fetch RUN}"
    mkdir -p "$out"
    (. "$creds" && aws s3 cp --recursive --quiet "s3://${BENCH_BUCKET:?}/$2/" "$out/")
    tail -3 "$out/detached.log" 2>/dev/null || echo "no detached.log: the run may still be going" >&2
    TF_VAR_db_password=x tf destroy -auto-approve -input=false -var-file=${TFVARS:-bench.tfvars} >/dev/null && echo destroyed || recover
    echo "=== reports in $out"
    exit ;;
  run) shift ;;
  *) echo "usage: run.sh plan | apply | run [matrix.sh arguments] | fetch RUN" >&2; exit 2 ;;
esac
: "${BENCH_DB_PASSWORD:?}" "${SSH_KEY:?}"
export TF_VAR_db_password=$BENCH_DB_PASSWORD
main=$$

destroy() {
  trap - EXIT INT TERM
  pkill -P "$heartbeat,$deadline" 2>/dev/null; kill "$heartbeat" "$deadline" 2>/dev/null || true # their sleeps too
  echo "=== destroy $(date +%T)"
  refresh || echo "credentials not refreshed: destroying with the last ones" >&2
  tf destroy -auto-approve -input=false -var-file=${TFVARS:-bench.tfvars} >/dev/null && echo destroyed || recover
  rm -rf "$(dirname "$creds")"
}

# every 5 minutes new credentials; the first refresh that fails ends the run
( while sleep "${HEARTBEAT_SECONDS:-300}"; do refresh || { echo "=== credentials expired $(date +%T)" >&2; kill -TERM $main; exit; }; done ) &
heartbeat=$!
disown $heartbeat
( sleep $((${MAX_RUN_MINUTES:-480} * 60)) && echo "=== MAX_RUN_MINUTES reached" >&2 && kill -TERM $main ) &
deadline=$!
disown $deadline
trap destroy EXIT
trap 'exit 1' INT TERM

echo "=== apply $(date +%T)"
tf apply -input=false bench.tfplan
client=$(tf output -raw client)
ssh_() { ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=60 "ubuntu@$client" "$@"; }
until ssh_ 'test -x /usr/lib/jvm/temurin-25-jdk-amd64/bin/java && test -x /snap/bin/aws' 2>/dev/null; do sleep 15; done

urls=$(tf output -json urls)
out="${RESULTS_DIR:-$here/results}/$(date +%Y%m%d-%H%M)"
mkdir -p "$out"
if [ "${NET_DELAY_US:-0}" != 0 ]; then
  # each database's host:port, from its JDBC URL
  targets=$(python3 -c '
import json, re, sys
for url in json.loads(sys.argv[1]).values():
    print(re.search(r"(?://|@)([^:/;]+):(\d+)", url).expand(r"\1:\2"))
' "$urls")
  echo "=== network delay $NET_DELAY_US µs"
  ssh_ "sudo bash -s -- $NET_DELAY_US $(echo $targets)" < "$here/net-delay.sh" | tee "$out/net-delay.txt"
fi
pids=(); cmds=()
for tier in a b; do
  args=$(python3 -c '
import json, shlex, sys
tier, urls = sys.argv[1], json.loads(sys.argv[2])
user = {"a": {"postgresql": "postgres", "oracle": "system", "mysql": "root", "mariadb": "root", "sqlserver": "sa"}}
for key, url in urls.items():
    t, engine = key.split("-", 1)
    if t == tier:
        name = user.get(tier, {}).get(engine, "bench")
        print(shlex.quote(f"{engine}={url}={name}"))  # a SQL Server URL holds ; for the remote shell
' "$tier" "$urls")
  [ -z "$args" ] && continue
  echo "=== tier $tier: $(echo $args | tr '\n' ' ')"
  # the working tree, tracked and untracked, without what git ignores (secrets, state, results)
  (cd "$repo" && git ls-files -co --exclude-standard -z | tar -c -f - --null -T -) |
    ssh_ "rm -rf $tier && mkdir $tier && tar -x -C $tier"
  copied=$tier
  # BASELINE=<commit>: that commit's tree beside it, for matrix.sh's BENCHBASE runs to compare with
  if [ -n "${BASELINE:-}" ]; then
    (cd "$repo" && git archive "$BASELINE") | ssh_ "rm -rf $tier-base && mkdir $tier-base && tar -x -C $tier-base"
  fi
  cmd="cd $tier && BENCH_DB_PASSWORD='$BENCH_DB_PASSWORD' REPS='${REPS:-3}' INFLIGHT='${INFLIGHT:-100 10000}' CHANGED='${CHANGED:-}' WRITERS='${WRITERS:-}' READS='${READS:-1}' BENCHBASE='${BENCHBASE:-}' BB_WORKLOADS='${BB_WORKLOADS:-}' BB_TIME='${BB_TIME:-}' BB_RUNS='${BB_RUNS:-}' BB_JVM_ARGS='${BB_JVM_ARGS:-}' BB_LAYOUTS='${BB_LAYOUTS:-}' NET_DELAY_US='${NET_DELAY_US:-0}' BASE_DIR='${BASELINE:+../$tier-base}' bash bench-env/matrix.sh $tier $(echo $args) -- $*"
  if [ -n "${BENCH_BUCKET:-}" ]; then cmds+=("$cmd"); continue; fi
  (
    ssh_ "$cmd" > "$out/$tier.log" 2>&1 || echo "tier $tier failed, see $out/$tier.log"
    mkdir -p "$out/$tier"
    scp -q -i "$SSH_KEY" "ubuntu@$client:$tier/results/*" "$out/$tier/" || true
  ) &
  pids+=($!)
done
if [ -n "${BENCH_BUCKET:-}" ]; then
  run=$(basename "$out")
  # only detached.sh in the background: a backgrounded `cat` would read /dev/null
  printf '%s\n' "${cmds[@]}" | ssh_ "umask 077 && cat > tiers.sh && cp $copied/bench-env/detached.sh . && { BUCKET='$BENCH_BUCKET' RUN='$run' \
    MAX_RUN_MINUTES='${MAX_RUN_MINUTES:-480}' setsid nohup bash detached.sh < /dev/null > detached.log 2>&1 & }"
  trap - EXIT INT TERM
  pkill -P "$heartbeat,$deadline" 2>/dev/null; kill "$heartbeat" "$deadline" 2>/dev/null || true # their sleeps too
  rm -rf "$(dirname "$creds")"
  echo "=== detached as $run: the client ends the instances itself; later: run.sh fetch $run"
  exit
fi
for p in "${pids[@]}"; do wait "$p"; done
echo "=== reports in $out"
