#!/usr/bin/env python3
"""Compare the probe's hand-written Vulkan structs with the real ABI.

    check_probe_layout.py <offsets-binary> <probe-python-file>

`offsets-binary` is tools/test/vulkan_offsets.c built against the Khronos headers; it prints one
line per field, `VkPhysicalDeviceProperties.deviceName 20`. The probe file is the python lifted out
of the guest script, which is loaded here for its declarations only - main() is never reached, so
nothing is opened and no GPU is needed.

Two kinds of mismatch are tolerated, both on purpose. A struct the probe does not declare at all is
skipped: the list in vulkan_offsets.c is a survey, not a requirement. And the probe sizes some
structs LARGER than the ABI, because a driver written against a newer Vulkan writes the whole struct
and the probe must not be the thing that turns that into memory corruption - so `size` is a lower
bound, never an equality.

Exits 0 when every field the two agree on agrees, 1 with a line per disagreement.
"""

import ctypes
import re
import subprocess
import sys


def load_declarations(path):
    """The module-level part of the probe: its classes and constants, without running it."""
    source = open(path, encoding="utf-8").read()
    # Everything the script does lives in main(); the block below it is the try/except that calls
    # main and turns its failures into the verdicts the app reads. Cut there, and refuse to guess
    # if the shape ever changes - a silently truncated file would check nothing and say it passed.
    marker = "\ntry:\n    main()"
    if marker not in source:
        raise SystemExit("FAIL: %s no longer has the expected 'try: main()' tail; update this "
                         "checker rather than let it check nothing" % path)
    body = source.split(marker, 1)[0]
    namespace = {"__name__": "probe_under_test"}
    exec(compile(body, path, "exec"), namespace)
    return namespace


def real_offsets(offsets_binary):
    """`{"VkPhysicalDeviceProperties.deviceName": 20, ...}` from the offsets binary's stdout."""
    table = {}
    printed = subprocess.run([offsets_binary], capture_output=True, text=True, check=True).stdout
    for line in printed.splitlines():
        line = line.strip()
        if not line or line.count(" ") != 1 or "." not in line.split(" ")[0]:
            continue
        name, offset = line.split(" ")
        try:
            table[name] = int(offset)
        except ValueError:
            continue
    if not table:
        raise SystemExit("FAIL: %s printed no offsets" % offsets_binary)
    return table


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip())
        return 2
    declarations = load_declarations(argv[2])
    table = real_offsets(argv[1])

    checked = 0
    bad = []
    for name, want in sorted(table.items()):
        structure, _, field = name.partition(".")
        probe_struct = declarations.get(structure)
        if not isinstance(probe_struct, type) or not issubclass(probe_struct, ctypes.Structure):
            continue
        checked += 1
        if field == "size":
            if ctypes.sizeof(probe_struct) < want:
                bad.append("%s: the probe reserves %d bytes, the ABI needs %d - a driver that "
                           "writes a whole struct would run past the end of it"
                           % (structure, ctypes.sizeof(probe_struct), want))
            continue
        declared = getattr(probe_struct, field, None)
        if declared is None:
            continue        # the probe does not name this field; it is not reading it the wrong way
        if declared.offset != want:
            direction = "high" if declared.offset > want else "low"
            bad.append("%s.%s: the probe reads it %d bytes %s (the ABI puts it at %d, the probe at %d)"
                       % (structure, field, abs(declared.offset - want), direction, want,
                          declared.offset))
    if not checked:
        bad.append("no struct in %s matched %s: the checker has silently stopped checking anything"
                   % (argv[2], argv[1]))

    if bad:
        print("  FAIL the probe's hand-written Vulkan structs do not match the real ABI")
        for line in bad:
            print("       " + line)
        return 1
    print("  ok   every Vulkan field the probe reads sits where the real header puts it "
          "(%d declarations checked)" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
