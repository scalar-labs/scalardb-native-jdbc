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
case "$*" in *matrix.sh*) sleep "$MATRIX_SECONDS" ;; *"test -x"*) ;; *) cat > /dev/null ;; esac
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
echo "run.sh teardown ok"
