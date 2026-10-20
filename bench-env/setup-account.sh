#!/bin/bash
# One-time setup in the shared account, with the user's own login, after the user approved it: the
# scalardb-bench role with iam-policy.json, assumable by the signed-in user only, and a $500 budget on
# the benchmark's tag. run.sh assumes the role itself; no profile is written anywhere.
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
