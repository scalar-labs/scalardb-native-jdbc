"""A benchmark plan: the workloads, and the combinations of level, layout and network delay to run
them at, as one JSON file (bench-env/plans/). matrix.sh runs a plan with PLAN=<file>, and the
plan replaces the BB_* variables and NET_DELAYS.

  python3 -I plan.py PLAN props   the Gradle properties every cell shares, one a line
  python3 -I plan.py PLAN cells   one cell a line: delay, the driver's level, layout, report prefix
  python3 -I plan.py PLAN show    the plan as it will run, for a review before a run

A plan:

  {
    "workloads": {                      # an id, the BenchBase benchmark it runs, and its settings
      "ycsb-a": {"benchmark": "ycsb", "weights": "50,0,0,50,0,0", "scale": 1000}
    },
    "matrix": {
      "workloads": ["ycsb-a"],          # run in this order in each cell
      "levels": ["READ_COMMITTED", "SNAPSHOT"],   # also SERIALIZABLE
      "layouts": ["decoupled", "inline"],
      "delays_us": [0]                  # microseconds added to each round trip (net-delay.sh)
    },
    "runs": "a,c",                      # optional: (a) the vendor, (c) the driver; also b, d
    "reps": 2, "time_s": 120, "terminals": 4,
    "loader_threads": 8,                # optional, BenchBase's sample otherwise
    "jvm_args": ["-Doracle.jdbc.implicitStatementCacheSize=128"]   # optional, the execute phase's
  }

A workload's weights are in the order of its BenchBase sample config (YCSB: read, insert, scan,
update, delete, read-modify-write); its scale is BenchBase's scale factor (YCSB: 1,000 rows each).
"""
import json
import sys

LEVELS = {"READ_COMMITTED": "READ_COMMITTED", "SNAPSHOT": "REPEATABLE_READ",
          "SERIALIZABLE": "SERIALIZABLE"}
TOP = {"workloads", "matrix", "runs", "reps", "time_s", "terminals", "loader_threads", "jvm_args",
       "description"}
WORKLOAD = {"benchmark", "weights", "scale"}
MATRIX = {"workloads", "levels", "layouts", "delays_us"}


def positive(x):
    return isinstance(x, int) and not isinstance(x, bool) and x > 0


def fail(message):
    sys.exit(f"plan: {message}")


def load(path):
    with open(path, encoding="utf-8") as f:
        p = json.load(f)
    for key in set(p) - TOP:
        fail(f"unknown key {key!r}")
    for key in ("workloads", "matrix", "reps", "time_s", "terminals"):
        if key not in p:
            fail(f"missing {key!r}")
    for key in ("reps", "time_s", "terminals", "loader_threads"):
        if key in p and not positive(p[key]):
            fail(f"{key}: a positive whole number")
    if "runs" in p and not (isinstance(p["runs"], str)
                            and set(p["runs"].split(",")) <= {"a", "b", "c", "d"}):
        fail("runs: a comma-separated subset of a, b, c, d")
    if not isinstance(p.get("jvm_args", []), list) or not all(
            isinstance(a, str) and a for a in p.get("jvm_args", [])):
        fail("jvm_args: a list of non-empty strings")
    for wid, w in p["workloads"].items():
        if set(w) - WORKLOAD or "benchmark" not in w:
            fail(f"workload {wid!r}: keys are {sorted(WORKLOAD)}, benchmark required")
        if not wid or any(c in wid for c in " ,\t"):
            fail(f"workload id {wid!r}: no spaces, tabs or commas")
        if "weights" in w and not (isinstance(w["weights"], str) and all(
                x.strip().isdigit() for x in w["weights"].split(","))):
            fail(f"workload {wid!r}: weights a string of comma-separated whole numbers")
        if "scale" in w and (isinstance(w["scale"], bool)
                             or not isinstance(w["scale"], (int, float)) or w["scale"] <= 0):
            fail(f"workload {wid!r}: scale a positive number")
    m = p["matrix"]
    if set(m) - MATRIX or set(MATRIX) - set(m):
        fail(f"matrix: keys are exactly {sorted(MATRIX)}")
    for key in MATRIX:
        if not isinstance(m[key], list) or not m[key]:
            fail(f"matrix.{key}: a non-empty list")
        if len(set(map(str, m[key]))) != len(m[key]):
            fail(f"matrix.{key}: an entry twice, whose cells would overwrite each other's reports")
    for wid in m["workloads"]:
        if wid not in p["workloads"]:
            fail(f"matrix names workload {wid!r}, which the plan does not define")
    for level in m["levels"]:
        if level not in LEVELS:
            fail(f"level {level!r}: one of {sorted(LEVELS)}")
    for layout in m["layouts"]:
        if layout not in ("decoupled", "inline"):
            fail(f"layout {layout!r}: decoupled or inline")
    for d in m["delays_us"]:
        if isinstance(d, bool) or not isinstance(d, int) or d < 0:
            fail(f"delay {d!r}: a whole number of microseconds")
    if any(" " in a or "\t" in a for a in p.get("jvm_args", [])):
        fail("a JVM argument with a space, which the harness splits on")
    return p


def props(p):
    out = [f"-Dnativejdbc.benchbase.workloads={','.join(p['matrix']['workloads'])}",
           f"-Dnativejdbc.benchbase.runs={p.get('runs', 'a,c')}",
           f"-Dnativejdbc.benchbase.reps={p['reps']}",
           f"-Dnativejdbc.benchbase.time={p['time_s']}",
           f"-Dnativejdbc.benchbase.terminals={p['terminals']}"]
    if "loader_threads" in p:
        out.append(f"-Dnativejdbc.benchbase.loaderThreads={p['loader_threads']}")
    if p.get("jvm_args"):
        out.append(f"-Dnativejdbc.benchbase.jvmArgs={' '.join(p['jvm_args'])}")
    for wid in p["matrix"]["workloads"]:
        w = p["workloads"][wid]
        out.append(f"-Dnativejdbc.benchbase.benchmark.{wid}={w['benchmark']}")
        if "weights" in w:
            out.append(f"-Dnativejdbc.benchbase.weights.{wid}={w['weights']}")
        if "scale" in w:
            out.append(f"-Dnativejdbc.benchbase.scale.{wid}={w['scale']}")
    return out


def cells(p):
    # as matrix.sh orders them: delay, level, layout, so a level's two layouts run back to back
    m = p["matrix"]
    for delay in m["delays_us"]:
        for level in m["levels"]:
            for layout in m["layouts"]:
                prefix = "bb" + ("" if layout == "decoupled" else "-" + layout)
                prefix += {"READ_COMMITTED": "-rc", "SERIALIZABLE": "-ser"}.get(level, "")
                prefix += f"-d{delay}"
                yield delay, LEVELS[level], layout, prefix


def main():
    path, what = sys.argv[1], sys.argv[2]
    p = load(path)
    if what == "props":
        print("\n".join(props(p)))
    elif what == "cells":
        for c in cells(p):
            print("\t".join(map(str, c)))
    elif what == "show":
        n = len(list(cells(p)))
        print(f"{path}: {n} cell(s) of {', '.join(p['matrix']['workloads'])},"
              f" {p['reps']} repetition(s) of {p['time_s']} s, {p['terminals']} terminals,"
              f" runs {p.get('runs', 'a,c')}")
        for c in cells(p):
            print(f"  {c[3]}: delay {c[0]} µs, driver level {c[1]}, {c[2]}")
        print("\n".join("  " + x for x in props(p)))
    else:
        fail(f"unknown command {what!r}")


if __name__ == "__main__":
    main()
