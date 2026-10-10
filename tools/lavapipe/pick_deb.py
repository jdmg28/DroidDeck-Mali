#!/usr/bin/env python3
"""Pick one package out of a Debian `Packages` index and print what CI needs to download it.

Written as a script rather than inline because the alternative is a wall of awk in a workflow, and
this decides which Mesa build ends up on a phone that reboots when its GPU faults: a silent
"whatever is newest" is exactly how a version nobody read gets shipped.

  pick_deb.py --packages Packages --package mesa-vulkan-drivers --expect-version 25.0.7

prints KEY=VALUE lines (VERSION, FILENAME, SIZE, SHA256) on stdout, and with --expect-version it
exits 1 when the archive's version does not start with that string - printing what it found instead,
so the failure says which version is actually in the suite. --expect-version "" accepts anything.
"""
import argparse
import os
import sys


def read_records(path):
    """Debian's index is stanzas separated by blank lines; fields are `Key: value`."""
    text = open(path, "r", encoding="utf-8", errors="replace").read()
    for stanza in text.split("\n\n"):
        record = {}
        for line in stanza.split("\n"):
            if not line or line.startswith(" "):
                continue                      # continuation lines belong to the previous field
            key, _, value = line.partition(":")
            record[key.strip()] = value.strip()
        if record.get("Package"):
            yield record


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--packages", required=True, help="the (decompressed) Packages index")
    ap.add_argument("--package", required=True)
    ap.add_argument("--expect-version", default="", help="refuse unless the version starts with this")
    ap.add_argument("--arch", default="arm64")
    args = ap.parse_args()

    if not os.path.isfile(args.packages):
        sys.exit("no Packages index at %s - the download failed, and guessing is not a fallback"
                 % args.packages)

    wanted = [r for r in read_records(args.packages)
              if r["Package"] == args.package and r.get("Architecture", args.arch) == args.arch]
    if not wanted:
        sys.exit("package %s (%s) is not in that index at all" % (args.package, args.arch))
    if len(wanted) > 1:
        sys.exit("package %s appears %d times (multiple suites in one index?); refusing to pick: %s"
                 % (args.package, len(wanted), ", ".join(r.get("Version", "?") for r in wanted)))

    rec = wanted[0]
    version, filename = rec.get("Version", ""), rec.get("Filename", "")
    if not filename:
        sys.exit("index entry for %s has no Filename" % args.package)
    if args.expect_version and not version.startswith(args.expect_version):
        sys.exit("%s in the archive is version %s, not %s. The packaging was asked to produce a "
                 "specific Mesa build; look at what changed before shipping this on a device whose "
                 "GPU fault reboots the phone. (Pass --expect-version '' to accept %s.)"
                 % (args.package, version, args.expect_version, version))

    print("VERSION=%s" % version)
    print("FILENAME=%s" % filename)
    print("SIZE=%s" % rec.get("Size", "0"))
    print("SHA256=%s" % rec.get("SHA256", ""))
    print("PKG=%s" % args.package)


if __name__ == "__main__":
    main()
