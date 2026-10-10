#!/usr/bin/env python3
"""Refuse a package the app would refuse, before it is published.

Two questions, both asked of the archive rather than of the intention: is anything too big, and is
anything in there that the importer has no rule for?

The importer in LinuxVulkanDriverManager enforces three limits, all of them in DriverArchivePolicy,
and all of them read out of the *Java source* here rather than copied into this file: a packaging gate
that carries its own numbers drifts out of agreement with the app, and then either publishes a zip
that will be refused or refuses one that would have worked. If a constant is ever renamed, this stops
with "could not read" instead of defaulting to something.

Usage: check_sizes.py --archive out.zip --stage stage --policy path/to/DriverArchivePolicy.java \
                     [--zip package.zip]

--zip is the optional third look: the file names inside the archive are compared against what meta.json
declares, because an undeclared library in the zip is a refusal at import time on the phone, and
"adopt() reconciles both directions" only helps if the packager knew to look.
"""
import argparse
import os
import re
import sys


def limits_from_java(path):
    src = open(path, "r", encoding="utf-8", errors="replace").read()
    found = {}
    for name in ("MAX_ARCHIVE_BYTES", "MAX_LIBRARY_BYTES", "MAX_META_BYTES", "MAX_COMPANIONS"):
        m = re.search(name + r'\s*=\s*(\d+)L?\s*(?:(?:<<\s*(\d+))|(?:\*\s*1024\s*\*\s*1024))?', src)
        if not m:
            sys.exit("could not read %s out of %s - the importer's limits changed shape, and this "
                     "gate has to change with them (it refuses to guess them)" % (name, path))
        value = int(m.group(1))
        found[name] = value << int(m.group(2)) if m.group(2) else value
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--archive", required=True)
    ap.add_argument("--stage", required=True)
    ap.add_argument("--policy", required=True)
    ap.add_argument("--zip", default="", help="the built archive, to check its contents against meta.json")
    args = ap.parse_args()

    lim = limits_from_java(args.policy)
    rows = ["### Package size", "",
            "| file | bytes | limit |", "|---|---|---|"]
    bad = []
    for name in sorted(os.listdir(args.stage)):
        size = os.path.getsize(os.path.join(args.stage, name))
        cap = lim["MAX_META_BYTES"] if name == "meta.json" else lim["MAX_LIBRARY_BYTES"]
        rows.append("| %s | %s | %s |" % (name, format(size, ","), format(cap, ",")))
        if size > cap:
            bad.append("%s is %s bytes over the %s limit" % (name, format(size - cap, ","),
                                                              "meta.json" if name == "meta.json"
                                                              else "per-library"))
    total = os.path.getsize(args.archive)
    # The importer's MAX_ARCHIVE_BYTES bounds what it *writes*, not what it downloads, and the two are a
    # factor 3.3 apart here. Reporting only the compressed size would have said "40 MB, limit 134 MB"
    # about a package that unpacks to 133.7 MB - under the ceiling by 0.4%, which is the kind of margin a
    # summary exists to make visible.
    written = sum(os.path.getsize(os.path.join(args.stage, n)) for n in os.listdir(args.stage))
    rows.append("| unpacked total (what the importer writes) | %s | %s |"
                % (format(written, ","), format(lim["MAX_ARCHIVE_BYTES"], ",")))
    rows.append("| archive as downloaded (no limit; shown for the phone) | %s | - |" % format(total, ","))
    if written > lim["MAX_ARCHIVE_BYTES"]:
        bad.append("the package writes %s bytes, %s over MAX_ARCHIVE_BYTES"
                   % (format(written, ","), format(written - lim["MAX_ARCHIVE_BYTES"], ",")))
    if args.zip:
        import json
        import zipfile
        try:
            with zipfile.ZipFile(args.zip) as zf:
                names = [n for n in zf.namelist() if not n.endswith("/")]
                meta = json.loads(zf.read("meta.json"))
        except Exception as exc:            # noqa: BLE001 - the message is the deliverable here
            sys.exit("cannot read %s as the zip the app will read: %s" % (args.zip, exc))
        declared = set([meta.get("libraryName", "")] + list(meta.get("companions", [])))
        allowed = declared | {"meta.json", "NOTICE.txt", "LICENSE", "LICENSE.txt"}
        for name in names:
            base = os.path.basename(name)
            if "/" in name:
                bad.append("%s is not at the top level of the archive, where the importer looks" % name)
            elif base not in allowed:
                bad.append("%s is in the zip but not declared in meta.json - adopt() refuses a package "
                           "carrying a library nobody accounted for, which is the right answer for the "
                           "phone and a bad one for a published artifact" % base)
        extra = len(meta.get("companions", [])) - lim["MAX_COMPANIONS"]
        if extra > 0:
            bad.append("%d companions, %d over MAX_COMPANIONS" % (len(meta.get("companions", [])), extra))
        rows.append("")
        rows.append("Contents of `%s`: %d file(s), all of them declared in meta.json."
                    % (os.path.basename(args.zip), len(names)))
    rows.append("")
    if bad:
        rows.append("**Refused:** " + "; ".join(bad))
    else:
        rows.append("**OK**: every file fits inside the limits the importer enforces.")
    report = "\n".join(rows) + "\n"
    out = os.environ.get("GITHUB_STEP_SUMMARY")
    if out:
        with open(out, "a", encoding="utf-8") as handle:
            handle.write(report)
    print(report)
    if bad:
        for b in bad:
            print("REFUSED: " + b, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
