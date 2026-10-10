#!/usr/bin/env bash
# Builds the DroidDeck Mali-G615 driver package: the adapter from this tree, around an upstream
# PanVK-kbase glibc backend.
#
#   UPSTREAM_ZIP=/path/to/PanVK-Kbase-g615-v11-csf-v0.1.0-rc2-5a07217f-EMULATOR.zip \
#   UPSTREAM_TAG=panvk-kbase-v0.1.0-rc2 \
#   UPSTREAM_SHA256=<sha of that zip, from its SHA256SUMS> \
#     bash drivers/mali-g615/build.sh out/
#
# Nothing in the app changes to accept the result. DriverArchivePolicy wants a zip holding exactly
# one libvulkan_*.so - here, the adapter - plus libpanvk_backend.so, and
# LinuxVulkanDriverManager.installDriver() generates the ICD manifest itself from the absolute path
# it installed the files to. That is also why the backend is renamed: the adapter finds its backend
# beside itself by name (DD_BACKEND_NAME), which is what keeps one driver package self-contained
# instead of relying on a library path the guest does not have.
#
# Needs unzip, zip, python3, and a compiler for aarch64 Linux/glibc (apt: gcc-aarch64-linux-gnu)
# plus the Khronos Vulkan headers (VULKAN_INCLUDE_DIR, or /usr/include).
#
# NATIVE=1 builds the adapter for the machine running this, for the test suite; such a package is
# not installable on the phone and the zip says so in its name.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${1:-$HERE/dist}"
SRC="$HERE/adapter/droiddeck_mali_icd.c"
CC="${CC:-aarch64-linux-gnu-gcc}"
[ "${NATIVE:-0}" = 1 ] && CC="${CC_NATIVE:-cc}"

[ -n "${UPSTREAM_ZIP:-}" ] || { echo "UPSTREAM_ZIP is required: the upstream -EMULATOR.zip to wrap"; exit 1; }
[ -f "$UPSTREAM_ZIP" ] || { echo "no such file: $UPSTREAM_ZIP"; exit 1; }
[ -f "$SRC" ] || { echo "no adapter source at $SRC"; exit 1; }

VK_INC="${VULKAN_INCLUDE_DIR:-/usr/include}"
[ -f "$VK_INC/vulkan/vulkan_core.h" ] || { echo "no vulkan/vulkan_core.h under $VK_INC"; exit 1; }
[ -f "$VK_INC/vulkan/vk_icd.h" ] || { echo "no vulkan/vk_icd.h under $VK_INC"; exit 1; }

# The package version is the adapter's, because that is the part this tree owns; the backend it
# carries is named in driverVersion, and both are recorded in meta.json with their digests.
ADAPTER_VERSION=$(sed -n 's/.*ICD adapter, version \([0-9.]*\)\..*/\1/p' "$SRC" | head -1)
[ -n "$ADAPTER_VERSION" ] || { echo "could not read the adapter version from $SRC"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
STAGE="$WORK/stage"
mkdir -p "$STAGE/src" "$STAGE/licenses" "$STAGE/upstream-provenance" "$OUT"

echo "== upstream: $(basename "$UPSTREAM_ZIP")"
if [ -n "${UPSTREAM_SHA256:-}" ]; then
    got=$(sha256sum "$UPSTREAM_ZIP" | cut -d' ' -f1)
    if [ "$got" != "$UPSTREAM_SHA256" ]; then
        echo "the upstream zip is not what its release says it is"
        echo "  expected $UPSTREAM_SHA256"
        echo "  found    $got"
        echo "Refusing to package it. Either the download is corrupt or upstream replaced the asset;"
        echo "check its SHA256SUMS before trusting a driver with a GPU fault that reboots the phone."
        exit 1
    fi
    echo "  sha256 verified"
fi
unzip -q -o "$UPSTREAM_ZIP" -d "$WORK/upstream"

BACKEND=$(find "$WORK/upstream" -type f -name 'libvulkan_panfrost*.so' | head -1)
[ -n "$BACKEND" ] || BACKEND=$(find "$WORK/upstream" -type f -name 'libvulkan_*.so' | head -1)
if [ -z "$BACKEND" ]; then
    echo "no Vulkan driver library inside $UPSTREAM_ZIP; it contains:"
    unzip -l "$UPSTREAM_ZIP" | sed 's/^/  /'
    exit 1
fi

# The two checks the importer on the phone will run, run here instead - so a package that the phone
# would reject never leaves this machine, and the reason is printed rather than guessed.
python3 - "$BACKEND" "${NATIVE:-0}" <<'PY'
import struct, sys
path, native = sys.argv[1], sys.argv[2] == "1"
data = open(path, "rb").read()      # 21 MB, and .dynstr is not guaranteed to be near the front
if data[:4] != b"\x7fELF" or data[4] != 2:
    sys.exit("%s is not a 64-bit ELF object" % path)
machine = struct.unpack_from("<H", data, 18)[0]
if not native and machine != 0xB7:
    sys.exit("%s is not AArch64 (e_machine 0x%x): the phone cannot load it" % (path, machine))
if b"libc.so.6" not in data:
    sys.exit("%s does not reference libc.so.6: it is a bionic/Android build, and this driver loads "
             "inside a glibc chroot" % path)
print("  backend %.1f MB, %s, glibc" % (
    len(data) / 1e6, "AArch64" if machine == 0xB7 else "e_machine 0x%x" % machine))
PY

cp "$BACKEND" "$STAGE/libpanvk_backend.so"
cp "$SRC" "$STAGE/src/droiddeck_mali_icd.c"
cp "$HERE/adapter/LICENSE.adapter" "$STAGE/LICENSE.adapter"
cp "$HERE/adapter/NOTICE.upstream.txt" "$STAGE/NOTICE.txt"
cp -r "$HERE/licenses/." "$STAGE/licenses/"
cp "$HERE/SOURCE.json" "$HERE/CORRESPONDING-SOURCE.json" "$STAGE/upstream-provenance/" 2>/dev/null || \
    echo "  (no provenance templates in the tree; the package will carry none)"

echo "== building the adapter with $CC"
# -fvisibility=hidden with DD_EXPORT on the four entry points is what keeps the ICD interface
# exactly four symbols: a stray vkDestroyInstance exported from the adapter would be picked up by
# the loader before the backend's, which is a whole class of wrong that costs a session to find.
$CC -O2 -std=gnu11 -fPIC -fvisibility=hidden -Wall -Wextra \
    -I"$VK_INC" -I"$HERE/adapter" \
    -shared -o "$STAGE/libvulkan_droiddeck_mali.so" "$SRC" -ldl -lpthread

python3 - "$STAGE" "${UPSTREAM_TAG:-unknown}" "$ADAPTER_VERSION" "${MESA_COMMIT:-}" <<'PY'
import hashlib, json, pathlib, sys

stage, tag, version, mesa = sys.argv[1:5]
def sha(name):
    return hashlib.sha256(pathlib.Path(stage, name).read_bytes()).hexdigest()

backend_sha, adapter_sha = sha("libpanvk_backend.so"), sha("libvulkan_droiddeck_mali.so")
meta = {
    "schemaVersion": 1,
    "kind": "linux-vulkan-icd",
    "name": "DroidDeck Mali-G615 (PanVK %s + experimental adapter)" % tag.split("-")[-1],
    "driverVersion": "Adapter %s / PanVK-kbase %s / Mesa %s" % (
        version, tag.replace("panvk-kbase-", ""), mesa[:12] or "unknown"),
    "minGlibc": "2.38",
    "libc": "glibc",
    "vulkanApiVersion": "1.4.363",
    "libraryName": "libvulkan_droiddeck_mali.so",
    "backendLibraryName": "libpanvk_backend.so",
    "gpu": "Mali-G615 MC6",
    "kernelInterface": "mali_kbase CSF",
    # Said out loud, because the alternative is someone reading this as a fix: the adapter adds no
    # rendering capability, and the fault this device produces is in the kernel path below it.
    "status": "experimental-not-device-validated",
    "needsDroidDeckIntegrationPatch": True,
    "rootRequiredByDesign": False,
    "panvkBinarySha256": backend_sha,
    "adapterBinarySha256": adapter_sha,
    "upstreamRelease": tag,
    "traceEnvironment": {
        "DD_ADAPTER_TRACE": "path of a file to append one line per GPU call to",
        "DD_ADAPTER_TRACE_MAX": "lines to write before stopping (default 8192)",
    },
    "note": "Not an Android system driver. Not a new GPU driver. The adapter adds the DRM-node "
            "facade gamescope's nested backend needs, and optionally a call trace; nothing else "
            "about the rendering path is changed.",
}
pathlib.Path(stage, "meta.json").write_text(json.dumps(meta, indent=2) + "\n")
pathlib.Path(stage, "upstream-provenance", "MANIFEST.json").write_text(json.dumps({
    "profile": "g615-v11-csf",
    "abi": "aarch64-linux-glibc",
    "upstreamRelease": tag,
    "mesaCommit": mesa,
    "backendBinarySha256": backend_sha,
    "adapterBinarySha256": adapter_sha,
    "packagedBy": "drivers/mali-g615/build.sh",
}, indent=2) + "\n")
print("== meta.json: backend %s… adapter %s…" % (backend_sha[:12], adapter_sha[:12]))
PY

suffix=""
[ "${NATIVE:-0}" = 1 ] && suffix="-NATIVE-NOT-INSTALLABLE"
NAME="DroidDeck-Mali-G615-driver-$ADAPTER_VERSION$suffix.zip"
( cd "$STAGE" && rm -f "$OUT/$NAME" && zip -q -r -X "$OUT/$NAME" . )

echo "== $OUT/$NAME"
unzip -l "$OUT/$NAME" | tail -3
sha256sum "$OUT/$NAME"
echo
echo "Install it on the phone: Components -> Controladores de GPU -> Importar .zip."
