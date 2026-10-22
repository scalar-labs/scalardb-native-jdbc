#!/bin/bash
# Checks run.sh's teardown with stand-ins for aws, terraform, ssh and scp: a run that ends, and a
# run whose login expires on the way, each destroy everything exactly once.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
t=$(mktemp -d)
trap 'rm -rf "$t"' EXIT
mkdir "$t/bin"
cat > "$t/bin/aws" <<'S'
#!/bin/bash
case "$*" in *get-caller-identity*) echo 123456789012; exit ;; esac
n=$(( $(cat "$T/aws" 2>/dev/null || echo 0) + 1 )); echo $n > "$T/aws"
[ "$n" -le "$AWS_OK_CALLS" ] && printf 'fake%s\tsecret\ttoken\n' "$n"
S
cat > "$t/bin/terraform" <<'S'
#!/bin/bash
shift # -chdir
echo "$1 key=$AWS_ACCESS_KEY_ID" >> "$T/terraform"
case "$1 $2" in
  "output -raw") echo 127.0.0.1 ;;
  "output -json") echo '{"a-postgresql": "jdbc:postgresql://x:5432/postgres"}' ;;
esac
S
cat > "$t/bin/ssh" <<'S'
#!/bin/bash
case "$*" in
  *tiers.sh*) cd "$T/remote" && bash -c "${@: -1}" ;; # the launch, in a real shell
  *matrix.sh*) sleep "$MATRIX_SECONDS" ;;
  *"test -x"*) ;;
  *) cat > /dev/null ;;
esac
S
printf '#!/bin/bash\n' > "$t/bin/scp"
chmod +x "$t/bin"/*
export PATH="$t/bin:$PATH" T="$t" BENCH_DB_PASSWORD=x SSH_KEY=x RESULTS_DIR="$t/results" HEARTBEAT_SECONDS=1

# a run that ends on its own: one apply, one destroy
AWS_OK_CALLS=1000 MATRIX_SECONDS=0 bash "$here/run.sh" run > "$t/out" 2>&1 || { cat "$t/out"; echo "FAIL: normal run exited non-zero"; exit 1; }
[ "$(grep -c '^apply' "$t/terraform")" = 1 ] && [ "$(grep -c '^destroy' "$t/terraform")" = 1 ] \
  || { cat "$t/out" "$t/terraform"; echo "FAIL: normal run"; exit 1; }

# the login expires during the matrix: destroyed with the last good credentials, the run fails
rm "$t/terraform" "$t/aws"
if AWS_OK_CALLS=3 MATRIX_SECONDS=60 perl -e 'alarm 30; exec @ARGV' bash "$here/run.sh" run > "$t/out" 2>&1; then
  echo "FAIL: an expired login must fail the run"; exit 1
fi
grep -q 'credentials expired' "$t/out" && [ "$(grep -c '^destroy key=fake3' "$t/terraform")" = 1 ] \
  || { cat "$t/out" "$t/terraform"; echo "FAIL: expired login"; exit 1; }
# a detached run: applied, started on the client with one line per tier, nothing destroyed from here
rm "$t/terraform" "$t/aws"
mkdir -p "$t/remote/a/bench-env" && printf 'echo started > started\n' > "$t/remote/a/bench-env/detached.sh"
command -v setsid > /dev/null || { printf '#!/bin/bash\nexec "$@"\n' > "$t/bin/setsid"; chmod +x "$t/bin/setsid"; } # macOS has none
AWS_OK_CALLS=1000 MATRIX_SECONDS=0 BENCH_BUCKET=bucket bash "$here/run.sh" run > "$t/out" 2>&1 || { cat "$t/out"; echo "FAIL: detached run exited non-zero"; exit 1; }
sleep 1 # detached.sh runs in the background
grep -q 'detached as' "$t/out" && [ "$(grep -c '^apply' "$t/terraform")" = 1 ] && ! grep -q '^destroy' "$t/terraform" \
  && [ "$(grep -c 'matrix.sh a ' "$t/remote/tiers.sh")" = 1 ] && [ -f "$t/remote/started" ] \
  || { cat "$t/out" "$t/terraform"; echo "FAIL: detached run"; exit 1; }

# detached.sh on the client: a tier that fails still ends in the upload and the end of every instance
# of its own VPC
mkdir -p "$t/home/a" "$t/home/b"
command -v timeout > /dev/null || printf '#!/bin/bash\nshift; exec "$@"\n' > "$t/bin/timeout" # macOS has none
chmod +x "$t/bin"/*
printf '%s\n' 'cd a && mkdir results && touch results/r.md' 'cd b && false' > "$t/home/tiers.sh"
cat > "$t/bin/aws" <<'S'
#!/bin/bash
echo "$*" >> "$T/calls"
case "$*" in *describe-db-instances*) echo bench-b-pg-1 ;; *describe-instances*) echo i-1 i-2 ;; esac
S
cat > "$t/bin/curl" <<'S'
#!/bin/bash
case "${@: -1}" in */token) echo tok ;; */mac) echo m1 ;; */macs/m1/vpc-id) echo vpc-1 ;; *) exit 22 ;; esac
S
chmod +x "$t/bin/curl"
HOME="$t/home" BUCKET=bucket RUN=r bash "$here/detached.sh" > "$t/home/detached.log" 2>&1
grep -q 's3 cp --recursive --quiet a/results s3://bucket/r/a/' "$t/calls" &&
  grep -q 'delete-db-instance --db-instance-identifier bench-b-pg-1' "$t/calls" &&
  grep -q 'terminate-instances --instance-ids i-1 i-2' "$t/calls" &&
  [ "$(grep -c vpc-1 "$t/calls")" = 2 ] && grep -q 'tier b failed' "$t/home/detached.log" \
  || { cat "$t/home/detached.log" "$t/calls"; echo "FAIL: detached.sh teardown"; exit 1; }
echo "run.sh teardown ok"
