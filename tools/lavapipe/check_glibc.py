#!/usr/bin/env python3
"""Decide whether a rootfs can actually load the libraries about to be shipped in a driver zip.

One comparison matters and it is not subtle: the highest GLIBC_* symbol version the object references
against the highest one the runtime's libc offers. A driver that needs more than the rootfs has does
not fail politely - it fails inside the guest, in the loader, thirty seconds into a session, with a
line in a log nobody reads until after the phone has rebooted. So this refuses to package.

Both sides come from scanning the files' bytes for `GLIBC_<major>.<minor>`, which needs no binutils
and works the same on either side:

  * in libc.so.6 that string set is the list of versions the library *defines*;
  * in a driver it is the list it *asks for*.

The asymmetry is worth knowing: a stray `GLIBC_2.40` in some Mesa warning string would make the
requirement look larger than it is, and the error is therefore always in the direction of refusing to
ship - which is the only direction that costs nothing here.

Usage:
  check_glibc.py --rootfs-libc libc.so.6 --rootfs-libdir rootfs/usr/lib \\
                 --lib libvulkan_lvp.so --lib libLLVM.so.19.1 --provides libLLVM.so.19.1

Exit 0 = ship it. 1 = do not (something is missing or newer than the runtime). 2 = the rootfs was not
readable, which is only acceptable with --allow-unverified, and never silently.
"""
import argparse
import os
import re
import subprocess
import sys

GLIBC_RE = re.compile(rb"GLIBC_(\d+)\.(\d+)")
NEEDED_PREFIX = b"NEEDED"


def max_version(path):
    """The highest GLIBC_x.y appearing in a file, as (major, minor); None when the file has none."""
    best = None
    with open(path, "rb") as handle:
        tail = b""
        while True:
            chunk = handle.read(1 << 20)
            if not chunk:
                break
            data = tail + chunk
            for m in GLIBC_RE.finditer(data):
                found = (int(m.group(1)), int(m.group(2)))
                if best is None or found > best:
                    best = found
            tail = data[-16:]        # a version string must not be cut in half by the chunk edge
    return best


def sonames_in(dirs):
    """Every `lib*.so*` file name anywhere under the given rootfs directories."""
    names = set()
    for base in dirs:
        for _root, _dirs, files in os.walk(base):
            for f in files:
                if ".so" in f:
                    names.add(f)
    return names


def needed_by(path, readelf):
    """The NEEDED entries of an ELF object, via binutils; None when binutils is not on hand."""
    try:
        out = subprocess.run([readelf, "-d", "-W", path], capture_output=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    found = []
    for line in out.splitlines():
        text = line.decode("utf-8", "replace")
        if "NEEDED" in text:
            m = re.search(r"\[([^\]]+)\]", text)
            if m:
                found.append(m.group(1))
    return found


def fmt(v):
    return "none" if v is None else "%d.%d" % v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rootfs-libc", default="",
                    help="libc.so.6 as extracted from the runtime; omit it for report mode, where the "
                         "requirement is measured and printed but nothing is compared")
    ap.add_argument("--rootfs-libdir", action="append", default=[], help="dir to search for NEEDED libs")
    ap.add_argument("--lib", action="append", default=[], help="library being shipped, checked and reported")
    ap.add_argument("--provides", action="append", default=[], help="sonames the zip itself supplies")
    ap.add_argument("--allow-missing", action="append", default=[], help="sonames allowed to be absent")
    ap.add_argument("--allow-unverified", action="store_true",
                    help="treat an unreadable rootfs as a warning instead of a refusal")
    args = ap.parse_args()

    lines = []
    problems = []
    unverified = False
    comparing = bool(args.rootfs_libc)
    if not comparing:
        have = None
    elif not os.path.isfile(args.rootfs_libc):
        unverified = True
        have = None
    else:
        have = max_version(args.rootfs_libc)

    shipped = set(args.provides)
    present = sonames_in(args.rootfs_libdir) if args.rootfs_libdir else set()
    if args.rootfs_libdir and not present:
        problems.append("no libraries found under %s - the rootfs extraction is wrong, and an empty "
                        "list is not evidence that the driver's dependencies are missing"
                        % ", ".join(args.rootfs_libdir))

    rows = []
    for lib in args.lib:
        if not os.path.isfile(lib):
            problems.append("missing library to check: %s" % lib)
            continue
        want = max_version(lib)
        rows.append((os.path.basename(lib), os.path.getsize(lib), want))
        if want and have and want > have:
            # The only failure a driver can have that the loader will report as "version not found".
            problems.append("%s references GLIBC_%s, but the runtime's libc only goes to %s. "
                            "The loader will refuse it inside the guest; build the driver against a "
                            "matching toolchain or ship it to a newer runtime, do not install it and "
                            "see." % (os.path.basename(lib), fmt(want), fmt(have)))
        needed = needed_by(lib, "readelf")
        if needed is None:
            lines.append("  (readelf unavailable: NEEDED of %s was not checked)" % os.path.basename(lib))
            continue
        for dep in needed:
            if dep in present or dep in shipped or dep in args.allow_missing:
                continue
            problems.append("%s needs %s and the runtime does not have it (nor does the zip provide "
                            "it). Find the package that carries it, or do not ship this driver."
                            % (os.path.basename(lib), dep))

    summary = ["### lavapipe compatibility", "",
               "| what | value |", "|---|---|",
               "| runtime glibc (highest version its libc defines) | %s |" % fmt(have)]
    for name, size, want in rows:
        summary.append("| %s | %.1f MB, needs at most GLIBC_%s |" % (name, size / 1e6, fmt(want)))
    summary.append("")
    if unverified:
        summary.append("**The runtime could not be inspected**: %s is not readable, so nothing above "
                       "was compared against a real glibc." % args.rootfs_libc)
    elif not comparing:
        summary.append("**Report mode**: no runtime libc was given, so the numbers above are the "
                       "requirement this package puts on a rootfs, not a verdict on any particular one. "
                       "The comparison that decides whether it loads is made on the device, by the "
                       "session script, against the glibc the guest actually has - CI cannot know it, "
                       "because the rootfs is the user's.")
    if problems or unverified:
        summary.append("**Refused:**")
        summary += ["- %s" % p for p in problems]
    else:
        summary.append("**OK**: every library's GLIBC requirement is inside what the runtime provides, "
                       "and every NEEDED entry is either in the runtime or shipped by the zip.")
    report = "\n".join(summary) + "\n"

    out = os.environ.get("GITHUB_STEP_SUMMARY")
    if out:
        with open(out, "a", encoding="utf-8") as handle:
            handle.write(report)
    print(report)
    for line in lines:
        print(line)

    if unverified and not problems:
        # The one case where a caller may decide to go ahead: it has to say so with a flag, and the
        # exit code still distinguishes it from a real pass (0 = checked and fine, 2 = not checked).
        if args.allow_unverified:
            print("::warning::the runtime could not be inspected; packaging continues because "
                  "--allow-unverified was passed")
            return 0
        print("REFUSED: the runtime's libc (%s) could not be read, so the glibc requirement is "
              "unverified. Fix the rootfs extraction, or pass --allow-unverified if you mean to ship "
              "a driver nobody checked." % args.rootfs_libc, file=sys.stderr)
        return 2
    for problem in problems:
        print("REFUSED: " + problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
