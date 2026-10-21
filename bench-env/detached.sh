#!/bin/bash
# Runs on the client, started by `run.sh run` when BENCH_BUCKET is set, so the run needs no laptop:
# each line of ~/tiers.sh (one tier's matrix.sh) in parallel within MAX_RUN_MINUTES, then, also after
# a failure, the reports and logs to s3://$BUCKET/$RUN/, the benchmark's RDS instances deleted and
# its EC2 instances terminated, this one last, with the client's instance role (client-policy.json).
# The VPC, security groups and key pair cost nothing and wait for `run.sh fetch`, which destroys them.
set -uo pipefail
export AWS_DEFAULT_REGION=${AWS_REGION:-us-east-1}
tag=scalardb-native-jdbc-bench
cd ~

teardown() {
  trap - EXIT INT TERM
  echo "=== teardown $(date +%T)"
  for t in a b; do
    [ -d "$t/results" ] && aws s3 cp --recursive --quiet "$t/results" "s3://$BUCKET/$RUN/$t/"
  done
  for f in *.log; do aws s3 cp --quiet "$f" "s3://$BUCKET/$RUN/$f"; done
  for id in $(aws rds describe-db-instances --output text \
    --query "DBInstances[?TagList[?Key=='project' && Value=='$tag']].DBInstanceIdentifier"); do
    aws rds delete-db-instance --db-instance-identifier "$id" --skip-final-snapshot \
      --delete-automated-backups > /dev/null && echo "deleting $id"
  done
  # the log's last upload; nothing after the terminate is kept
  aws s3 cp --quiet detached.log "s3://$BUCKET/$RUN/detached.log"
  aws ec2 terminate-instances --instance-ids $(aws ec2 describe-instances --output text \
    --filters "Name=tag:project,Values=$tag" Name=instance-state-name,Values=pending,running,stopping,stopped \
    --query 'Reservations[].Instances[].InstanceId') > /dev/null
}
trap teardown EXIT
trap 'exit 1' INT TERM

echo "=== start $(date +%T)"
while IFS= read -r cmd; do
  t=${cmd#cd }; t=${t%% *}
  timeout "${MAX_RUN_MINUTES:-480}m" bash -c "$cmd" > "$t.log" 2>&1 || echo "tier $t failed, see $t.log" &
done < tiers.sh
wait
echo "=== done $(date +%T)"
