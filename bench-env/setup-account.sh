#!/bin/bash
# One-time setup in the shared account, with the user's own login, after the user approved it: the
# scalardb-bench role with iam-policy.json, assumable by the signed-in user only, and a $500 budget on
# the benchmark's tag. run.sh assumes the role itself; no profile is written anywhere. For runs
# without the laptop (detached.sh): the bucket scalardb-bench-<account> for the reports, kept 90
# days, and the client's role scalardb-bench-client with client-policy.json, as an instance profile.
#
# Before: aws login --profile hiroyuki_yamada (the user signs in).
# Usage: ALERT_EMAIL=... bench-env/setup-account.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${ALERT_EMAIL:?}"
tag=scalardb-native-jdbc-bench
admin=(--profile "${AWS_LOGIN_PROFILE:-hiroyuki_yamada}")

me=$(aws sts get-caller-identity "${admin[@]}" --query Arn --output text)
account=$(aws sts get-caller-identity "${admin[@]}" --query Account --output text)
echo "=== as $me in $account"

trust=$(printf '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"%s"},"Action":["sts:AssumeRole","sts:SetSourceIdentity"]}]}' "$me")
if aws iam get-role "${admin[@]}" --role-name scalardb-bench >/dev/null 2>&1; then
  aws iam update-assume-role-policy "${admin[@]}" --role-name scalardb-bench --policy-document "$trust"
else
  aws iam create-role "${admin[@]}" --role-name scalardb-bench --max-session-duration 43200 \
    --assume-role-policy-document "$trust" --tags Key=project,Value=$tag \
    --description "The ScalarDB native JDBC benchmarks: EC2 and RDS tagged project=$tag only" >/dev/null
fi
aws iam put-role-policy "${admin[@]}" --role-name scalardb-bench --policy-name bench \
  --policy-document "file://$here/iam-policy.json"
echo "=== role scalardb-bench"

bucket=scalardb-bench-$account
if ! aws s3api head-bucket "${admin[@]}" --bucket "$bucket" 2>/dev/null; then
  aws s3api create-bucket "${admin[@]}" --bucket "$bucket" --region us-east-1 >/dev/null
fi
aws s3api put-public-access-block "${admin[@]}" --bucket "$bucket" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-tagging "${admin[@]}" --bucket "$bucket" --tagging "TagSet=[{Key=project,Value=$tag}]"
aws s3api put-bucket-lifecycle-configuration "${admin[@]}" --bucket "$bucket" --lifecycle-configuration \
  '{"Rules":[{"ID":"expire","Status":"Enabled","Filter":{},"Expiration":{"Days":90}}]}'
echo "=== bucket $bucket"

ec2trust='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
if ! aws iam get-role "${admin[@]}" --role-name scalardb-bench-client >/dev/null 2>&1; then
  aws iam create-role "${admin[@]}" --role-name scalardb-bench-client --assume-role-policy-document "$ec2trust" \
    --tags Key=project,Value=$tag --description "The benchmark client: uploads its reports, ends the tagged instances" >/dev/null
fi
aws iam put-role-policy "${admin[@]}" --role-name scalardb-bench-client --policy-name client \
  --policy-document "$(sed "s/BUCKET/$bucket/" "$here/client-policy.json")"
if ! aws iam get-instance-profile "${admin[@]}" --instance-profile-name scalardb-bench-client >/dev/null 2>&1; then
  aws iam create-instance-profile "${admin[@]}" --instance-profile-name scalardb-bench-client >/dev/null
  aws iam add-role-to-instance-profile "${admin[@]}" --instance-profile-name scalardb-bench-client \
    --role-name scalardb-bench-client
fi
echo "=== client role and instance profile scalardb-bench-client"

if ! aws budgets describe-budget "${admin[@]}" --account-id "$account" --budget-name scalardb-bench >/dev/null 2>&1; then
  aws budgets create-budget "${admin[@]}" --account-id "$account" \
    --budget "{\"BudgetName\":\"scalardb-bench\",\"BudgetType\":\"COST\",\"TimeUnit\":\"ANNUALLY\",
      \"BudgetLimit\":{\"Amount\":\"500\",\"Unit\":\"USD\"},
      \"CostFilters\":{\"TagKeyValue\":[\"user:project\$$tag\"]}}" \
    --notifications-with-subscribers "$(python3 -c '
import json, sys
print(json.dumps([{"Notification": {"NotificationType": "ACTUAL", "ComparisonOperator": "GREATER_THAN",
  "Threshold": t, "ThresholdType": "PERCENTAGE"},
  "Subscribers": [{"SubscriptionType": "EMAIL", "Address": sys.argv[1]}]} for t in (50, 80, 100)]))
' "$ALERT_EMAIL")"
fi
echo "=== budget scalardb-bench, alerts to $ALERT_EMAIL"
