#!/usr/bin/env python3
"""Ranks the read benchmark's forms per engine, rows in flight and query.

Usage: rank.py DIR [--flag 1.5]

Reads every read-overhead-<ENGINE>[-inflight<N>].md under DIR (several runs of one point are
medianed), ignores forms whose rows differed from BASE (`!`) or that failed, and prints per engine
and in-flight count: the best form and its ratio to BASE, DRIVER's ratio, and a flag where the best
form beats DRIVER by more than --flag times.
"""
import glob
import os
import re
import statistics
import sys

CELL = re.compile(r"([\d.]+) \(")


def parse(text):
    """{(query, mode): ms} and the set of (query, mode) whose rows differ, from one report."""
    times, wrong, modes = {}, set(), None
    for line in text.splitlines():
        if line.startswith("| query |"):
            modes = [c.strip() for c in line.strip().strip("|").split("|")][1:]
            continue
        if modes is None or not line.startswith("| ") or line.startswith("|---"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != len(modes) + 1 or "x)" not in cells[1]:
            continue  # the statement-length tables share the header, without ratios
        for mode, cell in zip(modes, cells[1:]):
            if "!" in cell:
                wrong.add((cells[0], mode))
            m = CELL.match(cell)
            if m:
                times[(cells[0], mode)] = float(m.group(1))
    return times, wrong


def rank(reports, flag):
    """[(query, best mode, best ratio, DRIVER ratio or None, flagged)] from one point's reports."""
    ratios, wrong, order = {}, set(), []
    for text in reports:
        times, bad = parse(text)
        wrong |= bad
        for (q, mode), ms in times.items():
            if q not in order:
                order.append(q)
            base = times.get((q, "BASE"))
            if base:
                ratios.setdefault((q, mode), []).append(ms / base)
    rows = []
    for q in order:
        med = {m: statistics.median(v) for (qq, m), v in ratios.items()
               if qq == q and (q, m) not in wrong and m not in ("BASE", "BASE_OPEN")}
        if not med:
            continue
        best = min(med, key=med.get)
        drv = med.get("DRIVER")
        rows.append((q, best, med[best], drv, drv is not None and drv > flag * med[best]))
    return rows


def main():
    args = sys.argv[1:]
    flag = 1.5
    if "--flag" in args:
        i = args.index("--flag")
        flag = float(args[i + 1])
        del args[i:i + 2]
    points = {}
    for f in sorted(glob.glob(os.path.join(args[0], "*read-overhead-*.md"))):
        m = re.search(r"read-overhead-([A-Z]+)(?:-.*?inflight(\d+))?", os.path.basename(f))
        points.setdefault((m.group(1), int(m.group(2) or 100)), []).append(open(f).read())
    for (engine, inflight), reports in sorted(points.items()):
        print(f"\n## {engine}, {inflight} rows in flight ({len(reports)} runs)\n")
        print("| query | best form | best | DRIVER | flag |\n|---|---|---|---|---|")
        for q, best, r, drv, flagged in rank(reports, flag):
            d = f"{drv:.1f}x" if drv is not None else "-"
            print(f"| {q} | {best} | {r:.1f}x | {d} | {'**' + f'{drv / r:.1f}x' + '**' if flagged else ''} |")


def selftest():
    report = """| query | BASE | DRIVER | SPLIT_AJ | VIEW_CASE |
|---|---|---|---|---|
| a | 1.00 (1.0x) [1] | 4.00 (4.0x) [5] | 2.00 (2.0x) [3] | 0.50 (0.5x) [1] ! |
| b | 2.00 (1.0x) [2] | 2.20 (1.1x) [3] | error | 9.00 (4.5x) [9] |
"""
    rows = rank([report, report], 1.5)
    assert rows[0][:2] == ("a", "SPLIT_AJ") and rows[0][4], rows  # VIEW_CASE's rows differ
    assert rows[1][1] == "DRIVER" and not rows[1][4], rows


if __name__ == "__main__":
    selftest()
    main()
