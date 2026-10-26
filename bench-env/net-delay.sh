#!/bin/bash
# Runs as root on the client, from run.sh's NET_DELAY_US: every packet the client sends to the
# databases waits DELAY_US more (Linux's netem), so each round trip to them is DELAY_US longer; other
# traffic (ssh, S3, packages) is not delayed. Prints each database's TCP connect time, median of 50,
# before and after, and fails if a round trip grew by less than three quarters of DELAY_US.
#
# Usage: net-delay.sh DELAY_US host:port...
set -euo pipefail
delay=$1; shift
# netem, a module or built in; without it the first tc below fails
modprobe sch_netem 2>/dev/null ||
  { apt-get install -y -q "linux-modules-extra-$(uname -r)" >/dev/null 2>&1 && modprobe sch_netem; } || true
ip_of() { getent ahostsv4 "$1" | awk 'NR == 1 { print $1 }'; }
# a TCP connect's median in microseconds: the handshake is one round trip
connect_us() {
  python3 -c '
import socket, statistics, sys, time
host, port = sys.argv[1], int(sys.argv[2])
# the database may still be starting (its host'"'"'s user data): up to 30 minutes
for _ in range(360):
    try:
        socket.create_connection((host, port), timeout=5).close()
        break
    except OSError:
        time.sleep(5)
times = []
for _ in range(50):
    t = time.perf_counter()
    socket.create_connection((host, port), timeout=5).close()
    times.append((time.perf_counter() - t) * 1e6)
print(round(statistics.median(times)))
' "$1" "$2"
}
first=$(ip_of "${1%:*}")
dev=$(ip -o route get "$first" | sed -n 's/.* dev \([^ ]*\).*/\1/p')
declare -A before
for target in "$@"; do before[$target]=$(connect_us "$(ip_of "${target%:*}")" "${target##*:}"); done
# a fourth band that only the filters below reach (prio's priomap uses the first three), delayed
tc qdisc replace dev "$dev" root handle 1: prio bands 4
tc qdisc replace dev "$dev" parent 1:4 handle 40: netem delay "${delay}us" limit 100000
for target in "$@"; do
  tc filter add dev "$dev" parent 1: protocol ip prio 1 u32 match ip dst "$(ip_of "${target%:*}")/32" flowid 1:4
done
echo "netem on $dev: ${delay} µs added to the databases' packets"
tc qdisc show dev "$dev"
ok=1
for target in "$@"; do
  after=$(connect_us "$(ip_of "${target%:*}")" "${target##*:}")
  echo "$target: TCP connect median ${before[$target]} µs before, $after µs after"
  [ $((after - before[$target])) -ge $((delay * 3 / 4)) ] || { echo "$target: the delay did not take" >&2; ok=0; }
done
[ $ok = 1 ]
