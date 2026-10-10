#!/usr/bin/env python3
"""Read the built zip the way the importer will, and refuse it here if it would be refused there.

Every rule below mirrors a check that lives in Java (DriverArchivePolicy and the reconciliation in
LinuxVulkanDriverManager.adopt). They are worth re-running on the artifact because the two ways a
packaged driver goes wrong are both silent at build time: a library in the archive that meta.json does
not declare (adopt() refuses the import, so the user sees a rejection with no clue that CI built it),
and a field shaped slightly off, which the importer keeps quietly at its default instead of failing.
"""
import argparse
import json
import re
import sys
import zipfile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("archive")
    args = ap.parse_args()

    with zipfile.ZipFile(args.archive) as zf:
        names = sorted(n for n in zf.namelist() if not n.endswith("/"))
        try:
            meta = json.loads(zf.read("meta.json"))
        except Exception as exc:                                    # noqa: BLE001
            sys.exit("no readable meta.json at the top level of %s (%s): the importer looks for exactly "
                     "that path" % (args.archive, exc))

    problems = []
    libraries = [n for n in names if re.search(r"\.so(\.[0-9.]+)?$", n)]
    for n in names:
        if "/" in n:
            problems.append("%s is nested; the importer only reads the top level" % n)
    if meta.get("kind") != "linux-vulkan-icd":
        problems.append("kind is %r; the importer will not treat this as a Linux ICD" % meta.get("kind"))
    drivers = [n for n in libraries if n.startswith("libvulkan_")]
    if len(drivers) != 1:
        problems.append("the importer wants exactly one libvulkan_*.so and the archive has %s" % drivers)
    main_lib = meta.get("libraryName", "")
    if main_lib not in names:
        problems.append("libraryName is %r and no such file is in the archive" % main_lib)
    companions = list(meta.get("companions", []))
    for declared in companions:
        if declared not in names:
            problems.append("meta.json declares %s and the archive does not carry it" % declared)
    for lib in libraries:
        if lib not in [main_lib] + companions:
            problems.append("%s is in the archive but undeclared - adopt() refuses the package over "
                            "this, by design, so that nothing the app has no rule for is loaded "
                            "because it happened to be next door" % lib)
    if not re.match(r"^\d+\.\d+$", str(meta.get("minGlibc", ""))):
        problems.append("minGlibc %r is not a version: droiddeck-session compares the guest's glibc "
                        "against it, and an unparseable value is an off switch" % meta.get("minGlibc"))
    if not re.match(r"^1\.[0-4]\.\d+$", str(meta.get("vulkanApiVersion", ""))):
        problems.append("vulkanApiVersion %r is outside the form apiVersion() keeps; anything else is "
                        "dropped in favour of the default" % meta.get("vulkanApiVersion"))
    if meta.get("libc") != "glibc":
        problems.append("libc is %r; a bionic build cannot load in the guest" % meta.get("libc"))
    sha = meta.get("sha256", {})
    for name in [main_lib] + companions:
        if name in sha and not re.fullmatch(r"[0-9a-f]{64}", str(sha[name])):
            problems.append("meta.json's sha256 for %s is not a digest" % name)

    if problems:
        for problem in problems:
            print("REFUSED: " + problem, file=sys.stderr)
        return 1
    print("OK: %s - %d librar%s, one libvulkan_*.so, minGlibc %s, ICD api %s, companions declared both "
          "ways" % (meta.get("name"), len(libraries), "y" if len(libraries) == 1 else "ies",
                    meta.get("minGlibc"), meta.get("vulkanApiVersion")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
