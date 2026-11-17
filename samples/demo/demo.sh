#!/bin/bash
# The demo of ScalarDB Native JDBC: the EC and point systems of docs/getting-started.md, on one
# tmux screen. See README.md for the run sheet.
#   ./demo.sh up      start both databases with the plain tables
#   ./demo.sh tmux    the demo screen: EC on the left, the point system on the right
#   ./demo.sh import [ec|point]  import a database's shared tables into ScalarDB and set them up for
#                     the driver; both without an argument (act 1, from each local pane's shell)
#   ./demo.sh reset   back to before the import: a wallet of 10000 and 500 points, no orders or history
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
# ScalarDB's Schema Loader: the release's jar here, which up downloads, unless SCHEMA_LOADER names one
SL_VERSION=3.20.1
export SCHEMA_LOADER=${SCHEMA_LOADER:-$(pwd)/scalardb-schema-loader-$SL_VERSION.jar}

need_loader() {
  [ -f "$SCHEMA_LOADER" ] || { echo "No Schema Loader at $SCHEMA_LOADER: run ./demo.sh up first" >&2; exit 1; }
}

# Runs a command quietly; prints its output if it fails
quiet() {
  local out
  out=$("$@" 2>&1) || { printf '%s\n' "$out"; return 1; }
}

# Whether the setup has run on the database's two shared tables (it creates a read view for each)
imported() {
  [ "$(docker exec native-$1 psql -U postgres -Atc \
    "SELECT count(*) FROM pg_views WHERE schemaname = '$1' AND viewname LIKE 'rc\_%'")" = 2 ]
}

# Whether both databases are set up
import_done() { imported ec && imported point; }

# The frontend reads ScalarDB's tables when it starts, so it starts after both imports
start_frontend() {
  docker rm -f native-frontend >/dev/null 2>&1 || true
  docker run -d --name native-frontend --network native-net -p 127.0.0.1:15432:5432 \
    -v "$GS/frontend.properties:/scalardb-postgres-frontend/scalardb.properties:ro" \
    ghcr.io/scalar-labs/scalardb-postgres-frontend:3.20.1 >/dev/null
  until docker logs native-frontend 2>&1 | grep -q Listening; do sleep 1; done
}

# Whether the Schema Loader has imported the database's tables (an import cut short leaves them so)
in_scalardb() {
  [ "$(docker exec native-$1 psql -U postgres -Atc "SELECT to_regclass('scalardb.metadata') IS NOT NULL")" = t ] &&
    [ "$(docker exec native-$1 psql -U postgres -Atc \
      "SELECT count(*) > 0 FROM scalardb.metadata WHERE full_table_name LIKE '$1.%'")" = t ]
}

# The plain tables and the Coordinator table, as before the import
create_tables() {
  local db
  for db in ec point; do docker exec -i native-$db psql -q -U postgres < "$GS/$db.sql"; done
  quiet java -jar "$SCHEMA_LOADER" --config "$GS/ec.properties" --coordinator
}

# Runs the queries every 2 s, through the driver once the database is imported (the driver refuses a
# database without ScalarDB's catalog) and with psql before; keeps the last good output when a read
# meets a commit in flight
watch_loop() {
  local db=$1 url=$2 port=$3; shift 3
  local args=() q
  for q in "$@"; do args+=(-c "$q"); done
  local out
  read_once() {
    if imported $db; then "$SNSQL" -U postgres "$url" "${args[@]}"
    else LC_MESSAGES=C psql -h localhost -p $port -U postgres "${args[@]}"; fi
  }
  while true; do
    if out=$(read_once 2>&1); then
      clear; printf '%s\n' "$out"
    fi
    sleep 2
  done
}

# The pane scripts handle each failure themselves rather than exit on it; Ctrl-C in a waiting pane
# closes it (./demo.sh tmux opens the screen again)
case "${1:-}" in _local|_global|_watch-*) set +e ;; esac

case "${1:-}" in
  up)
    if [ ! -f "$SCHEMA_LOADER" ]; then
      echo "Downloading the Schema Loader $SL_VERSION"
      curl -fL --progress-bar -o "$SCHEMA_LOADER.part" \
        https://github.com/scalar-labs/scalardb/releases/download/v$SL_VERSION/scalardb-schema-loader-$SL_VERSION.jar
      mv "$SCHEMA_LOADER.part" "$SCHEMA_LOADER"
    fi
    (cd "$REPO" && ./gradlew -q :shell:installDist :tools:installDist </dev/null)
    docker network create native-net >/dev/null
    for db in ec:5432 point:5433; do
      docker run -d --name native-${db%:*} --network native-net -p ${db#*:}:5432 \
        -e POSTGRES_PASSWORD=postgres -e POSTGRES_INITDB_ARGS="--locale=C --encoding=UTF8" \
        postgres:17 >/dev/null
    done
    for db in ec point; do
      until docker exec native-$db pg_isready -U postgres -h localhost >/dev/null 2>&1; do sleep 1; done
    done
    create_tables ;;
  import)
    need_loader
    for db in ${2:-ec point}; do
      case $db in ec) url=$EC ;; point) url=$POINT ;; *) echo "No database $db: ec or point" >&2; exit 1 ;; esac
      if in_scalardb $db; then echo "$db: already imported"
      else
        echo "\$ java -jar scalardb-schema-loader.jar --config $db.properties -f import-$db.json --import"
        quiet java -jar "$SCHEMA_LOADER" --config "$GS/$db.properties" -f "$GS/import-$db.json" --import
      fi
      echo "\$ scalardb-native-tools setup $url -U postgres --no-wait"
      "$TOOLS" setup $url -U postgres --no-wait
    done ;;
  _local) # a local pane: psql before the import, then a shell for the import and snsql
    db=$2; url=$EC; port=5432; name=EC
    [ $db = point ] && { url=$POINT; port=5433; name=Point; }
    if ! imported $db; then
      tmux select-pane -t "$TMUX_PANE" -T "$name · local: psql → $name DB"
      echo "\$ psql -h localhost -p $port -U postgres"
      LC_MESSAGES=C psql -h localhost -p $port -U postgres
    fi
    # The shell: the import is typed; snsql with the driver's URL is in the history (up arrow)
    rc=$(mktemp)
    cat > "$rc" <<RC
PS1='$db\$ '
cd "$(pwd)"
PATH=$(dirname "$SNSQL"):\$PATH
HISTFILE=/dev/null
HISTIGNORE='./demo.sh*'
history -s "snsql -U postgres $url"
snsql() {
  tmux select-pane -t "\$TMUX_PANE" -T "$name · local: snsql → Native JDBC → $name DB"
  command snsql "\$@"
  tmux select-pane -t "\$TMUX_PANE" -T "$name · local: shell"
}
rm -f "$rc"
RC
    tmux select-pane -t "$TMUX_PANE" -T "$name · local: shell"
    BASH_SILENCE_DEPRECATION_WARNING=1 exec "$BASH" --rcfile "$rc" -i ;;
  tmux)
    tmux kill-session -t $SESSION 2>/dev/null || true
    need_loader
    # Panes don't inherit this script's environment
    me="env SCHEMA_LOADER=$SCHEMA_LOADER PGPASSWORD=postgres $(pwd)/demo.sh"
    # Two columns, EC and point, so the top and bottom rows share one boundary; then the EC top
    # pane splits into local and global
    ec_local=$(tmux new-session -d -s $SESSION -x "$(tput cols)" -y "$(tput lines)" -P -F '#{pane_id}' \
      "$me _local ec")
    point_local=$(tmux split-window -h -l 34% -t "$ec_local" -P -F '#{pane_id}' "$me _local point")
    ec_state=$(tmux split-window -v -l 35% -t "$ec_local" -P -F '#{pane_id}' "$me _watch-ec")
    point_state=$(tmux split-window -v -l 35% -t "$point_local" -P -F '#{pane_id}' "$me _watch-point")
    ec_global=$(tmux split-window -h -l 50% -t "$ec_local" -P -F '#{pane_id}' \
      "$me _global")
    tmux set -t $SESSION pane-border-status top
    tmux set -t $SESSION pane-border-format ' #{pane_title} '
    tmux set -t $SESSION mouse on
    tmux select-pane -t "$ec_global" -T 'EC · global: psql → PG frontend → EC + point DBs'
    tmux select-pane -t "$ec_state" -T 'EC DB · every 2 s'
    tmux select-pane -t "$point_state" -T 'Point DB · every 2 s'
    tmux select-pane -t "$ec_local"
    if [ -n "${TMUX:-}" ]; then exec tmux switch-client -t $SESSION; else exec tmux attach -t $SESSION; fi
    ;;
  _global) # the global pane: starts the frontend once both databases are imported, then psql on it
    until import_done; do clear; echo 'Waiting for both databases to be imported'; sleep 2; done
    if ! psql -h localhost -p 15432 -U postgres -d ec -c '' >/dev/null 2>&1; then
      echo 'Starting the PostgreSQL frontend'; start_frontend
    fi
    while true; do LC_MESSAGES=C psql -h localhost -p 15432 -U postgres -d ec; sleep 1; done ;;
  _watch-ec)
    watch_loop ec $EC 5432 "SELECT * FROM ec.wallet" \
      "SELECT item, price, paid_money, paid_points FROM ec.orders ORDER BY item" ;;
  _watch-point)
    watch_loop point $POINT 5433 "SELECT * FROM point.point_account" \
      "SELECT delta, reason FROM point.point_history ORDER BY reason" ;;
  reset)
    need_loader
    docker exec -e PGOPTIONS='-c client_min_messages=warning' native-ec psql -q -U postgres \
      -c "DROP SCHEMA IF EXISTS ec, scalardb, coordinator CASCADE"
    docker exec -e PGOPTIONS='-c client_min_messages=warning' native-point psql -q -U postgres \
      -c "DROP SCHEMA IF EXISTS point, scalardb CASCADE"
    docker rm -f native-frontend >/dev/null 2>&1 || true
    create_tables
    # The top panes start again: psql in the local ones, the global one waits for the imports
    tmux list-panes -s -t $SESSION -F '#{pane_id} #{pane_title}' 2>/dev/null |
      awk '/· (local|global):/ {print $1}' | xargs -n1 tmux respawn-pane -k -t || true ;;
  down)
    tmux kill-session -t $SESSION 2>/dev/null || true
    docker rm -f native-frontend native-ec native-point >/dev/null 2>&1 || true
    docker network rm native-net >/dev/null 2>&1 || true ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
