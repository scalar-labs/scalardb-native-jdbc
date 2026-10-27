"""A BenchBase execute phase's JFR recording: the JVM's CPU, and its CPU samples by owner.

Usage: python3 -I jfr-cpu.py RUN_DIR [COMMITS]
RUN_DIR holds execute.jfr; COMMITS (the server's count) gives the CPU per commit.
Samples are jdk.CPUTimeSample where the platform has it (Linux), else jdk.ExecutionSample.
"""
import collections
import json
import subprocess
import sys

run = sys.argv[1]
commits = int(sys.argv[2]) if len(sys.argv) > 2 else 0
jfr = f"{run}/execute.jfr"


def events(name):
    out = subprocess.run(
        ["jfr", "print", "--json", "--stack-depth", "64", "--events", name, jfr],
        capture_output=True, text=True, check=True).stdout
    return json.loads(out)["recording"]["events"]


OWNERS = [
    ("jsqlparser", "net.sf.jsqlparser."),
    ("driver", "com.scalar.db.nativejdbc."),
    ("pgjdbc", "org.postgresql."),
    ("ojdbc", "oracle.jdbc."),
    ("ojdbc", "oracle.net."),
    ("ojdbc", "oracle.sql."),
    ("benchbase", "com.oltpbenchmark."),
]

load = events("jdk.CPULoad")
if load:
    user = sum(e["values"]["jvmUser"] for e in load) / len(load)
    system = sum(e["values"]["jvmSystem"] for e in load) / len(load)
    print(f"JVM CPU: user {100 * user:.1f}%, system {100 * system:.1f}% of the machine "
          f"({len(load)} samples)")
cores = None
for e in events("jdk.CPUInformation"):
    cores = e["values"]["hwThreads"]
if load and cores:
    secs = len(load)  # one CPULoad a second
    cpu = (user + system) * cores * secs
    print(f"JVM CPU time: {cpu:.1f} s over about {secs} s on {cores} hardware threads"
          + (f", {1000 * cpu / commits:.3f} ms per commit" if commits else ""))

samples = events("jdk.CPUTimeSample") or events("jdk.ExecutionSample")
kind = "CPU-time" if samples and samples[0]["type"] == "jdk.CPUTimeSample" else "execution"
by_owner = collections.Counter()
by_driver = collections.Counter()
by_thread = collections.Counter()
for e in samples:
    v = e["values"]
    thread = (v.get("eventThread") or v.get("sampledThread") or {}).get("javaName") or "?"
    by_thread[thread.split("-")[0]] += 1
    frames = [f["method"]["type"]["name"].replace("/", ".") + "." + f["method"]["name"]
              for f in ((v.get("stackTrace") or {}).get("frames") or [])]
    owner = "jdk/other"
    for f in frames:
        o = next((n for n, p in OWNERS if f.startswith(p)), None)
        if o:
            owner = o
            break
    by_owner[owner] += 1
    drv = [f for f in frames if f.startswith("com.scalar.db.nativejdbc.")]
    if drv:
        by_driver[drv[0].replace("com.scalar.db.nativejdbc.", "")] += 1
total = max(len(samples), 1)
print(f"{kind} samples: {len(samples)}; threads: {by_thread.most_common(5)}")
for o, n in by_owner.most_common():
    print(f"  {o:10s} {n:7d} {100 * n / total:5.1f}%")
incl = collections.Counter()
for e in samples:
    v = e["values"]
    frames = [f["method"]["type"]["name"].replace("/", ".") + "." + f["method"]["name"]
              for f in ((v.get("stackTrace") or {}).get("frames") or [])]
    for f in set(x for x in frames if x.startswith("com.scalar.db.nativejdbc.")):
        incl[f.replace("com.scalar.db.nativejdbc.", "")] += 1
print("driver methods, inclusive:")
for f, n in incl.most_common(30):
    print(f"  {n:6d} {100 * n / total:5.1f}%  {f}")
print("innermost driver method:")
for f, n in by_driver.most_common(20):
    print(f"  {n:6d} {100 * n / total:5.1f}%  {f}")
