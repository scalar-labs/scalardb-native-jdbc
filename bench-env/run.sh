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
#
# Before either: aws login --profile hiroyuki_yamada (the user signs in). run.sh then works only as
# the role scalardb-bench (setup-account.sh), assumed anew on every refresh.
# Environment: BENCH_DB_PASSWORD, SSH_KEY (the private key of bench.tfvars' public one),
# MAX_RUN_MINUTES (default 480, below a login session of 12 hours, with room for the destroy).
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
  run) shift ;;
  *) echo "usage: run.sh plan | run [matrix.sh arguments]" >&2; exit 2 ;;
esac
: "${BENCH_DB_PASSWORD:?}" "${SSH_KEY:?}"
export TF_VAR_db_password=$BENCH_DB_PASSWORD
main=$$

destroy() {
  trap - EXIT INT TERM
  kill "$heartbeat" "$deadline" 2>/dev/null || true
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
until ssh_ 'test -x /usr/lib/jvm/temurin-25-jdk-amd64/bin/java' 2>/dev/null; do sleep 15; done

urls=$(tf output -json urls)
out="${RESULTS_DIR:-$here/results}/$(date +%Y%m%d-%H%M)"
mkdir -p "$out"
pids=()
for tier in a b; do
  args=$(python3 -c '
import json, sys
tier, urls = sys.argv[1], json.loads(sys.argv[2])
user = {"a": {"postgresql": "postgres", "oracle": "system", "mysql": "root", "mariadb": "root"}}
for key, url in urls.items():
    t, engine = key.split("-", 1)
    if t == tier:
        name = user.get(tier, {}).get(engine, "bench")
        print(f"{engine}={url}={name}")
' "$tier" "$urls")
  [ -z "$args" ] && continue
  echo "=== tier $tier: $(echo $args | tr '\n' ' ')"
  # the working tree, tracked and untracked, without what git ignores (secrets, state, results)
  (cd "$repo" && git ls-files -co --exclude-standard -z | tar -c -f - --null -T -) |
    ssh_ "rm -rf $tier && mkdir $tier && tar -x -C $tier"
  (
    ssh_ "cd $tier && BENCH_DB_PASSWORD='$BENCH_DB_PASSWORD' REPS='${REPS:-3}' INFLIGHT='${INFLIGHT:-100 10000}' bash bench-env/matrix.sh $tier $(echo $args) -- $*" \
      > "$out/$tier.log" 2>&1 || echo "tier $tier failed, see $out/$tier.log"
    mkdir -p "$out/$tier"
    scp -q -i "$SSH_KEY" "ubuntu@$client:$tier/results/*" "$out/$tier/" || true
  ) &
  pids+=($!)
done
for p in "${pids[@]}"; do wait "$p"; done
echo "=== reports in $out"
