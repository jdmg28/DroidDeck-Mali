#!/usr/bin/env python3
"""Write the meta.json the DroidDeck driver importer expects next to the staged libraries.

Kept as a file rather than a heredoc in the workflow for one reason: the workflow's `run: |` block is
indented, and Python reads indentation as syntax.

The fields are not decoration. `kind` is what makes the importer treat the zip as a Linux ICD at all;
`libraryName` must equal the file on disk because the object is renamed to it; `companions` is the
list adopt() reconciles against the archive, in both directions, so a file in the zip that is not
declared here is a refusal, not a silent drop; `minGlibc` is what the app shows before installing.
"""
import argparse
import hashlib
import json
import pathlib


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", required=True)
    ap.add_argument("--main-library", required=True)
    ap.add_argument("--companion", action="append", default=[])
    ap.add_argument("--suite", default="trixie")
    ap.add_argument("--mesa-version", default="")
    ap.add_argument("--llvm-soname", default="")
    ap.add_argument("--min-glibc", default="", help="highest GLIBC_* the libraries reference, if known")
    ap.add_argument("--name", default="")
    args = ap.parse_args()

    stage = pathlib.Path(args.stage)
    def sha(name):
        return hashlib.sha256((stage / name).read_bytes()).hexdigest()

    meta = {
        "schemaVersion": 1,
        "kind": "linux-vulkan-icd",
        "name": args.name or ("Software Vulkan (Mesa lavapipe, Debian %s)" % args.suite),
        "driverVersion": "Mesa %s / %s" % (args.mesa_version or "?", args.llvm_soname or "?"),
        "minGlibc": args.min_glibc,
        "libc": "glibc",
        "vulkanApiVersion": "1.3.204",
        "libraryName": args.main_library,
        "companions": list(args.companion),
        "gpuVendor": "software",
        "note": ("llvmpipe behind Vulkan: nothing that draws through this touches the GPU. Slow on "
                 "purpose - it is a way to test Steam, FEX, Proton and the games with the vendor "
                 "driver out of the picture, not a way to play."),
        "sha256": {n: sha(n) for n in [args.main_library] + list(args.companion)
                   if (stage / n).is_file()},
    }
    (stage / "meta.json").write_text(json.dumps(meta, indent=2) + "\n")
    print("meta.json: %s + %s, %d companion(s)" % (args.main_library, meta["driverVersion"],
                                                    len(args.companion)))


if __name__ == "__main__":
    main()
