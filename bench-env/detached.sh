#!/bin/bash
# Runs on the client, started by `run.sh run` when BENCH_BUCKET is set, so the run needs no laptop:
# each line of ~/tiers.sh (one tier's matrix.sh) in parallel within MAX_RUN_MINUTES, then, also after
# a failure, the reports and logs to s3://$BUCKET/$RUN/, the benchmark's RDS instances deleted and
# its EC2 instances terminated, this one last, with the client's instance role (client-policy.json).
# Only this run's: the ones in the client's VPC, since each apply has its own and runs can overlap.
# The VPC, security groups and key pair cost nothing and wait for `run.sh fetch`, which destroys them.
set -uo pipefail
export AWS_DEFAULT_REGION=${AWS_REGION:-us-east-1}
tag=scalardb-native-jdbc-bench
cd ~
imds() { curl -sf -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"; }
token=$(curl -sf -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
vpc=$(imds "network/interfaces/macs/$(imds mac)/vpc-id")
echo "=== vpc ${vpc:?not found in the instance metadata}"

teardown() {
  trap - EXIT INT TERM
  echo "=== teardown $(date +%T)"
  for t in a b; do
    [ -d "$t/results" ] && aws s3 cp --recursive --quiet "$t/results" "s3://$BUCKET/$RUN/$t/"
  done
  for f in *.log; do aws s3 cp --quiet "$f" "s3://$BUCKET/$RUN/$f"; done
  for id in $(aws rds describe-db-instances --output text \
    --query "DBInstances[?DBSubnetGroup.VpcId=='$vpc' && TagList[?Key=='project' && Value=='$tag']].DBInstanceIdentifier"); do
    aws rds delete-db-instance --db-instance-identifier "$id" --skip-final-snapshot \
      --delete-automated-backups > /dev/null && echo "deleting $id"
  done
  # the log's last upload; nothing after the terminate is kept
  aws s3 cp --quiet detached.log "s3://$BUCKET/$RUN/detached.log"
  aws ec2 terminate-instances --instance-ids $(aws ec2 describe-instances --output text \
    --filters "Name=tag:project,Values=$tag" "Name=vpc-id,Values=$vpc" Name=instance-state-name,Values=pending,running,stopping,stopped \
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
