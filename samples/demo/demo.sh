#!/bin/bash
# The demo of ScalarDB Native JDBC: the EC and point systems of docs/getting-started.md, on one
# tmux screen. See README.md for the run sheet.
#   ./demo.sh up      start both databases and the PostgreSQL frontend, import and set up the tables
#   ./demo.sh tmux    the demo screen: EC on the left, the point system on the right
#   ./demo.sh reset   back to a wallet of 10000 and 500 points, no orders or history
#   ./demo.sh down    remove the containers, the network and the tmux session
set -euo pipefail
cd "$(dirname "$0")"
REPO=$(cd ../.. && pwd)
GS=$REPO/samples/getting-started # the guide's SQL, import files and configurations
SNSQL=$REPO/shell/build/install/scalardb-native-shell/bin/snsql
TOOLS=$REPO/tools/build/install/scalardb-native-tools/bin/scalardb-native-tools
EC=jdbc:scalardb-native:postgresql://localhost:5432/postgres
POINT=jdbc:scalardb-native:postgresql://localhost:5433/postgres
export SNSQL_PASSWORD=postgres SCALARDB_NATIVE_PASSWORD=postgres
SESSION=native-demo

# Runs the queries every 2 s; keeps the last good output when a read meets a commit in flight
watch_loop() {
  local url=$1; shift
  local args=() q
  for q in "$@"; do args+=(-c "$q"); done
  local out
  while true; do
    if out=$("$SNSQL" -U postgres "$url" "${args[@]}" 2>&1); then
      clear; printf '%s\n' "$out"
    fi
    sleep 2
  done
}

case "${1:-}" in
  up)
    : "${SCHEMA_LOADER:?set SCHEMA_LOADER to scalardb-schema-loader-3.20.0.jar}"
    (cd "$REPO" && ./gradlew -q :shell:installDist :tools:installDist </dev/null)
    docker network create native-net >/dev/null
    for db in ec:5432 point:5433; do
      docker run -d --name native-${db%:*} --network native-net -p ${db#*:}:5432 \
        -e POSTGRES_PASSWORD=postgres -e POSTGRES_INITDB_ARGS="--locale=C --encoding=UTF8" \
        postgres:17 >/dev/null
    done
    for db in ec point; do
      until docker exec native-$db pg_isready -U postgres -h localhost >/dev/null 2>&1; do sleep 1; done
      docker exec -i native-$db psql -q -U postgres < "$GS/$db.sql"
      java -jar "$SCHEMA_LOADER" --config "$GS/$db.properties" -f "$GS/import-$db.json" --import >/dev/null 2>&1
    done
    java -jar "$SCHEMA_LOADER" --config "$GS/ec.properties" --coordinator >/dev/null 2>&1
    "$TOOLS" setup $EC -U postgres --no-wait >/dev/null
    "$TOOLS" setup $POINT -U postgres --no-wait >/dev/null
    docker run -d --name native-frontend --network native-net -p 127.0.0.1:15432:5432 \
      -v "$GS/frontend.properties:/scalardb-postgres-frontend/scalardb.properties:ro" \
      ghcr.io/scalar-labs/scalardb-postgres-frontend:3.20.1 >/dev/null
    until docker logs native-frontend 2>&1 | grep -q Listening; do sleep 1; done
    "$TOOLS" check $EC -U postgres
    "$TOOLS" check $POINT -U postgres
    ;;
  tmux)
    tmux kill-session -t $SESSION 2>/dev/null || true
    me=$(pwd)/demo.sh
    snsql="env SNSQL_PASSWORD=postgres $SNSQL -U postgres" # panes don't inherit this script's environment
    # Two columns, EC and point, so the top and bottom rows share one boundary; then the EC top
    # pane splits into local and global
    ec_local=$(tmux new-session -d -s $SESSION -x "$(tput cols)" -y "$(tput lines)" -P -F '#{pane_id}' \
      "$snsql $EC")
    point_local=$(tmux split-window -h -l 34% -t "$ec_local" -P -F '#{pane_id}' "$snsql $POINT")
    ec_state=$(tmux split-window -v -l 35% -t "$ec_local" -P -F '#{pane_id}' "$me _watch-ec")
    point_state=$(tmux split-window -v -l 35% -t "$point_local" -P -F '#{pane_id}' "$me _watch-point")
    ec_global=$(tmux split-window -h -l 50% -t "$ec_local" -P -F '#{pane_id}' \
      "docker exec -it native-ec psql -h native-frontend -U postgres -d ec")
    tmux set -t $SESSION pane-border-status top
    tmux set -t $SESSION pane-border-format ' #{pane_title} '
    tmux set -t $SESSION mouse on
    tmux select-pane -t "$ec_local" -T 'EC · local: snsql → Native JDBC → EC DB'
    tmux select-pane -t "$ec_global" -T 'EC · global: psql → PG frontend → EC + point DBs'
    tmux select-pane -t "$point_local" -T 'Point · local: snsql → Native JDBC → point DB'
    tmux select-pane -t "$ec_state" -T 'EC DB · every 2 s'
    tmux select-pane -t "$point_state" -T 'Point DB · every 2 s'
    tmux select-pane -t "$ec_local"
    if [ -n "${TMUX:-}" ]; then exec tmux switch-client -t $SESSION; else exec tmux attach -t $SESSION; fi
    ;;
  _watch-ec)
    watch_loop $EC "SELECT * FROM ec.wallet" \
      "SELECT item, price, paid_money, paid_points FROM ec.orders ORDER BY item" ;;
  _watch-point)
    watch_loop $POINT "SELECT * FROM point.point_account" \
      "SELECT delta, reason FROM point.point_history ORDER BY reason" ;;
  reset)
    "$SNSQL" -U postgres $EC -c "DELETE FROM ec.orders" \
      -c "UPDATE ec.wallet SET balance = 10000 WHERE customer_id = 1" >/dev/null
    docker exec native-ec psql -q -U postgres -c "TRUNCATE ec.topup_history"
    "$SNSQL" -U postgres $POINT -c "DELETE FROM point.point_history" \
      -c "UPDATE point.point_account SET points = 500 WHERE customer_id = 1" >/dev/null ;;
  down)
    tmux kill-session -t $SESSION 2>/dev/null || true
    docker rm -f native-frontend native-ec native-point >/dev/null 2>&1 || true
    docker network rm native-net >/dev/null 2>&1 || true ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
