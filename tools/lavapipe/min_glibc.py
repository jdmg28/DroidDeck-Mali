#!/usr/bin/env python3
"""Take the requirement out of check_glibc.py's report and write it as the package's minGlibc.

A separate script for a boring reason: the workflow's `run: |` blocks are indented, and Python reads
indentation as syntax - an inline heredoc is a one-character mistake away from being a SyntaxError in
a step nobody re-runs.

Refuses to produce a blank answer. minGlibc is the number droiddeck-session compares the guest's own
glibc against, so writing "" here would silently turn the device-side check off, which is the opposite
of the point.
"""
import argparse
import re
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", required=True, help="check_glibc.py's output, captured")
    ap.add_argument("--out", required=True, help="file to write the version into")
    args = ap.parse_args()

    text = open(args.report, encoding="utf-8", errors="replace").read()
    found = [tuple(map(int, m.groups()))
             for m in re.finditer(r"needs at most GLIBC_(\d+)\.(\d+)", text)]
    if not found:
        sys.exit("the report names no GLIBC requirement, so minGlibc would be a guess. Fix the report "
                 "step (exit %s) rather than publishing a driver whose device-side check is disabled."
                 % ("?"))
    best = max(found)
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write("%d.%d" % best)
    print("minGlibc = %d.%d (the highest of %s measured in this package)"
          % (best[0], best[1], ", ".join("%d.%d" % v for v in sorted(set(found)))))


if __name__ == "__main__":
    main()
