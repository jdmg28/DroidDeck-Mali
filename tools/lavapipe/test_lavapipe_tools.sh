#!/usr/bin/env bash
# Tests for the two scripts the lavapipe packaging workflow trusts its judgement to.
#
# They are worth testing separately because a bug in them is not a crash: it is a driver that gets
# packaged anyway and then fails inside the guest, or a good driver refused forever. Both look like
# success from CI's point of view. Everything here is synthetic - no downloads, no network, a second
# of runtime - including a case built to sit exactly on the chunk boundary of the byte scanner.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FAILED=0

ok()   { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; FAILED=1; }

# ---- a Packages index in the shape Debian writes: stanzas, continuation lines, two arches --------
cat > "$W/Packages" <<'IDX'
Package: libllvm19
Version: 1:19.1.7-3
Architecture: arm64
Filename: pool/main/l/llvm-toolchain-19/libllvm19_19.1.7-3_arm64.deb
Size: 44000000
SHA256: aaaa

Package: mesa-vulkan-drivers
Version: 25.0.7-1
Architecture: arm64
Multi-Arch: same
Description: Mesa Vulkan drivers
 This continuation line must not become a field.
Filename: pool/main/m/mesa/mesa-vulkan-drivers_25.0.7-1_arm64.deb
Size: 3000000
SHA256: bbbb

Package: mesa-vulkan-drivers
Version: 25.0.7-1
Architecture: amd64
Filename: pool/main/m/mesa/mesa-vulkan-drivers_25.0.7-1_amd64.deb
Size: 3100000
IDX

out=$(python3 "$HERE/pick_deb.py" --packages "$W/Packages" --package mesa-vulkan-drivers \
        --expect-version 25.0.7 2>&1); st=$?
if [ "$st" = 0 ] && printf '%s' "$out" | grep -qx "VERSION=25.0.7-1" \
   && printf '%s' "$out" | grep -q "FILENAME=pool/main/m/mesa/" \
   && ! printf '%s' "$out" | grep -q "amd64"; then
    ok "picks the arm64 record and only its fields"
else
    fail "picks the arm64 record and only its fields"; printf '%s\n' "$out" | sed 's/^/       /'
fi

python3 "$HERE/pick_deb.py" --packages "$W/Packages" --package mesa-vulkan-drivers \
    --expect-version 25.2.0 >"$W/o" 2>&1; st=$?
if [ "$st" = 1 ] && grep -q "is version 25.0.7-1, not 25.2.0" "$W/o"; then
    ok "refuses a suite that moved off the pinned Mesa, and names what it found"
else
    fail "refuses a suite that moved off the pinned Mesa"; cat "$W/o" | sed 's/^/       /'
fi

python3 "$HERE/pick_deb.py" --packages "$W/Packages" --package mesa-vulkan-drivers \
    --expect-version "" >/dev/null 2>&1 && ok "accepts anything when no version is pinned" \
    || fail "accepts anything when no version is pinned"

printf '\nPackage: mesa-vulkan-drivers\nVersion: 25.1.0-1\nArchitecture: arm64\nFilename: x\n\n' \
    >> "$W/Packages"
python3 "$HERE/pick_deb.py" --packages "$W/Packages" --package mesa-vulkan-drivers \
    --expect-version "" >"$W/o" 2>&1; st=$?
if [ "$st" = 1 ] && grep -q "appears 2 times" "$W/o"; then
    ok "refuses to guess between two matching records (the continuation line did not split one)"
else
    fail "refuses to guess between two matching records"; sed 's/^/       /' "$W/o"
fi

# ---- the glibc gate -------------------------------------------------------------------------------
# A rootfs libc defining 2.28..2.39, and a driver asking for 2.40: the exact case that must not ship.
python3 - "$W" <<'PY'
import pathlib, sys
w = pathlib.Path(sys.argv[1])
(w / "libc.so.6").write_bytes(b"GLIBC_2.28 GLIBC_2.34 GLIBC_2.39 " + b"\0" * 200)
(w / "new.so").write_bytes(b"GLIBC_2.40 needed " + b"\0" * 100)
(w / "old.so").write_bytes(b"GLIBC_2.31 needed " + b"\0" * 100)
# And the boundary case: a version string split by the scanner's own 1 MiB chunk, which is the one
# thing about a hand-written byte scan that could silently miss the highest number.
edge = bytearray(b"\0" * (1 << 20))
edge[(1 << 20) - 6:(1 << 20) + 4] = b"GLIBC_2.40"
(w / "edge.so").write_bytes(bytes(edge))
rootfs = w / "rootfs"
for name in ("libfoo.so.1", "libbar.so.2"):
    p = rootfs / "usr" / "lib" / "aarch64-linux-gnu" / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(b"\x7fELF")
PY

check() {   # check <expect-exit> <label> <args...>
    local want="$1" label="$2"; shift 2
    local out status
    out=$(python3 "$HERE/check_glibc.py" "$@" 2>&1); status=$?
    if [ "$status" = "$want" ]; then
        ok "$label"
    else
        fail "$label (exit $status, wanted $want)"; printf '%s\n' "$out" | sed 's/^/       /'
    fi
    LAST_OUT="$out"
}

check 1 "a driver needing a newer glibc than the runtime has is refused" \
    --rootfs-libc "$W/libc.so.6" --rootfs-libdir "$W/rootfs/usr/lib" --lib "$W/new.so"
printf '%s\n' "$LAST_OUT" | grep -q "only goes to 2.39" \
    && ok "  ...and the message says both numbers" || fail "  ...and the message says both numbers"

check 0 "an older build passes" \
    --rootfs-libc "$W/libc.so.6" --rootfs-libdir "$W/rootfs/usr/lib" --lib "$W/old.so"

# The boundary case is a *refusal* (2.40 > the fake runtime's 2.39) - what it proves is that the
# scanner saw the number at all, cut as it is across the 1 MiB chunk edge. A scanner that missed it
# would exit 0 here, which is the silent failure worth testing for.
check 1 "a version string split across the scanner's chunk boundary is still found" \
    --rootfs-libc "$W/libc.so.6" --rootfs-libdir "$W/rootfs/usr/lib" --lib "$W/edge.so"
printf '%s\n' "$LAST_OUT" | grep -q "needs at most GLIBC_2.40" \
    && ok "  ...and it is the number the refusal quotes" || fail "  ...and it quotes the number"

# A real ELF, so the NEEDED list is not something this test invented: this glibc needs exactly
# ld-linux-x86-64.so.2, which is absent from the fake rootfs and present in the next run.
REAL=$(readlink -f /lib/*/libc.so.6 /lib/libc.so.6 2>/dev/null | head -1)
if [ -n "$REAL" ] && command -v readelf >/dev/null; then
    if [ -n "$REAL" ] && command -v readelf >/dev/null; then
    # The combination the packaging job actually runs, and the one this suite missed until it failed in
    # CI: a real ELF's NEEDED list with no rootfs to compare against. Judging that against an empty set
    # refuses every driver ever built, so it has to be a report.
    check 0 "a real binary's dependencies are reported, not refused, when no rootfs was given" \
        --lib "$REAL"
    printf '%s\n' "$LAST_OUT" | grep -q "needs: ld-" \
        && ok "  ...and the list is still printed" || fail "  ...and the list is still printed"
fi
check 1 "a NEEDED library neither the runtime nor the zip provides is refused" \
        --rootfs-libc "$W/libc.so.6" --rootfs-libdir "$W/rootfs/usr/lib" --lib "$REAL"
    printf 'cp "$W/../" /dev/null 2>/dev/null; mkdir -p "$W/rootfs/usr/lib/ld" && touch \
        "$W/rootfs/usr/lib/ld/ld-linux-x86-64.so.2"
' > /dev/null
    mkdir -p "$W/rootfs/usr/lib/ld" && touch "$W/rootfs/usr/lib/ld/ld-linux-x86-64.so.2"
    check 0 "  ...and passes once the runtime has it" \
        --rootfs-libc "$W/libc.so.6" --rootfs-libdir "$W/rootfs/usr/lib" --lib "$REAL"
    rm -rf "$W/rootfs/usr/lib/ld"
    # --provides is how the zip declares "the companion covers this": the same refusal must go away.
    NEEDS=$(readelf -d -W "$REAL" | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p' | head -1)
    check 0 "a dependency the zip itself supplies is not held against it" \
        --rootfs-libc "$W/libc.so.6" --rootfs-libdir "$W/rootfs/usr/lib" --lib "$REAL" \
        --provides "$NEEDS"
else
    echo "  skip NEEDED cases (no readelf or no /lib libc here)"
fi
check 1 "an empty rootfs directory is called out instead of read as 'nothing is needed'" \
    --rootfs-libc "$W/libc.so.6" --rootfs-libdir "$W/nope" --lib "$W/old.so"
# Report mode is what the packaging workflow runs: CI has no way to know which rootfs the phone will
# have, so it measures the requirement and says so, instead of inventing a runtime to compare against.
check 0 "without --rootfs-libc it reports the requirement and passes" \
    --lib "$W/new.so" --rootfs-libdir "$W/rootfs/usr/lib"
printf '%s\n' "$LAST_OUT" | grep -qF "Report mode" \
    && ok "  ...and says plainly that nothing was compared" || fail "  ...says plainly nothing was compared"
check 1 "report mode still refuses a dependency nobody provides" \
    --lib "$W/old.so" --rootfs-libdir "$W/nope"
check 2 "an unreadable rootfs is a loud 2, not a 0, and names the reason" \
    --rootfs-libc "$W/absent" --lib "$W/old.so"
printf '%s\n' "$LAST_OUT" | grep -q "unverified" \
    && ok "  ...and the message says the requirement is unverified" || fail "  ...says so"
check 0 "and only a warning when someone passes --allow-unverified on purpose" \
    --rootfs-libc "$W/absent" --lib "$W/old.so" --allow-unverified
check 1 "but a real problem is still a refusal even with --allow-unverified" \
    --rootfs-libc "$W/absent" --lib "$W/new.so" --rootfs-libdir "$W/nope" --allow-unverified

# ---- the package-shape gate ---------------------------------------------------------------------
# It reads the limits out of DriverArchivePolicy.java instead of carrying its own, so this fixture is
# also the test that the parsing still matches the Java source. If a constant is renamed the script
# must say so loudly, which is what the last case checks with a policy file that is not the real one.
mkdir -p "$W/stage"
python3 - "$W" <<'FIX'
import json, os, pathlib, sys, zipfile
w = pathlib.Path(sys.argv[1]); stage = w / "stage"
(stage / "libvulkan_lvp.so").write_bytes(b"\x7fELF" + b"\0" * 400)
(stage / "libLLVM.so.19.1").write_bytes(b"\x7fELF" + b"\0" * 400)
(stage / "NOTICE.txt").write_text("notice\n")
(stage / "meta.json").write_text(json.dumps({
    "kind": "linux-vulkan-icd", "libraryName": "libvulkan_lvp.so",
    "companions": ["libLLVM.so.19.1"]}, indent=2))
with zipfile.ZipFile(w / "good.zip", "w") as z:
    for n in sorted(os.listdir(stage)):
        z.write(stage / n, n)
(stage / "libstray.so").write_bytes(b"\x7fELF")
with zipfile.ZipFile(w / "stray.zip", "w") as z:
    for n in sorted(os.listdir(stage)):
        z.write(stage / n, n)
FIX
POLICY=app/src/main/java/com/droiddeck/launcher/gpu/DriverArchivePolicy.java
[ -f "$POLICY" ] || POLICY="$HERE/../../$POLICY"
if python3 "$HERE/check_sizes.py" --archive "$W/good.zip" --stage "$W/stage" --policy "$POLICY" \
        --zip "$W/good.zip" >"$W/o" 2>&1 && grep -qF "**OK**" "$W/o"; then
    ok "a declared package passes the size and contents gate"
else
    fail "a declared package passes the size and contents gate"; sed 's/^/       /' "$W/o"
fi
python3 "$HERE/check_sizes.py" --archive "$W/good.zip" --stage "$W/stage" --policy "$POLICY" \
    --zip "$W/stray.zip" >"$W/o" 2>&1; st=$?
if [ "$st" = 1 ] && grep -q "libstray.so is in the zip but not declared" "$W/o"; then
    ok "an undeclared library in the archive is refused, and names itself"
else
    fail "an undeclared library in the archive is refused (exit $st)"; sed 's/^/       /' "$W/o"
fi
printf 'class P { }\n' > "$W/Fake.java"
python3 "$HERE/check_sizes.py" --archive "$W/good.zip" --stage "$W/stage" --policy "$W/Fake.java" \
    >"$W/o" 2>&1; st=$?
if [ "$st" != 0 ] && grep -q "could not read MAX_ARCHIVE_BYTES" "$W/o"; then
    ok "and it refuses to invent the limits when the Java source changes shape"
else
    fail "and it refuses to invent the limits"; sed 's/^/       /' "$W/o"
fi

# ---- the archive-shape gate and the minGlibc recorder -------------------------------------------
# The fixture's meta.json is missing the fields the last two cases refuse, so fill them in first and
# check that a complete package passes, then break one field at a time.
python3 - "$W" <<'FIX2'
import json, os, pathlib, sys, zipfile
w = pathlib.Path(sys.argv[1]); stage = w / "stage2"
stage.mkdir(exist_ok=True)
(stage / "libvulkan_lvp.so").write_bytes(b"\x7fELF")
(stage / "libLLVM.so.19.1").write_bytes(b"\x7fELF")
(stage / "meta.json").write_text(json.dumps({
    "kind": "linux-vulkan-icd", "name": "t", "libraryName": "libvulkan_lvp.so",
    "companions": ["libLLVM.so.19.1"], "libc": "glibc", "minGlibc": "2.39",
    "vulkanApiVersion": "1.3.204"}))
with zipfile.ZipFile(w / "full.zip", "w") as z:
    for n in sorted(os.listdir(stage)):
        z.write(stage / n, n)
meta = json.loads((stage / "meta.json").read_text()); meta["minGlibc"] = ""
(stage / "meta.json").write_text(json.dumps(meta))
with zipfile.ZipFile(w / "nomin.zip", "w") as z:
    for n in sorted(os.listdir(stage)):
        z.write(stage / n, n)
meta["minGlibc"] = "2.39"; meta["companions"] = []
(stage / "meta.json").write_text(json.dumps(meta))
with zipfile.ZipFile(w / "undecl.zip", "w") as z:
    for n in sorted(os.listdir(stage)):
        z.write(stage / n, n)
# Two shapes the gate has rules for and this suite had no fixture for: a companion the metadata promises
# and the archive does not carry, and a libraryName pointing at a file that is not there. Each is refused
# on the phone too (adopt() for the companion, the icd.json path for the main library), so publishing one
# with a green check mark is the same mistake the 96 MiB ceiling was - only quieter, because the refusal
# arrives on somebody else's device a week later.
meta["companions"] = ["libLLVM.so.19.1", "libghost.so.2"]
(stage / "meta.json").write_text(json.dumps(meta))
with zipfile.ZipFile(w / "short.zip", "w") as z:      # neither of those two is in this archive
    z.write(stage / "libvulkan_lvp.so", "libvulkan_lvp.so")
    z.write(stage / "meta.json", "meta.json")
meta["companions"] = ["libLLVM.so.19.1"]; meta["libraryName"] = "libvulkan_zzz.so"
(stage / "meta.json").write_text(json.dumps(meta))
with zipfile.ZipFile(w / "swap.zip", "w") as z:       # the file it names is not the file it carries
    for n in sorted(os.listdir(stage)):
        z.write(stage / n, n)
FIX2
for case in "full.zip:0" "nomin.zip:1" "undecl.zip:1" "short.zip:1" "swap.zip:1"; do
    z=${case%%:*}; want=${case##*:}
    python3 "$HERE/check_archive.py" "$W/$z" >"$W/o" 2>&1; st=$?
    if [ "$st" = "$want" ]; then ok "check_archive: $z exits $want as expected"
    else fail "check_archive: $z (exit $st, wanted $want)"; sed 's/^/       /' "$W/o"; fi
done
grep -q "not a version" "$W/o" 2>/dev/null || true
python3 "$HERE/check_archive.py" "$W/undecl.zip" 2>&1 | grep -qF "libLLVM.so.19.1 is in the archive but undeclared" \
    && ok "  ...and the undeclared case names the file" || fail "  ...and the undeclared case names the file"
python3 "$HERE/check_archive.py" "$W/nomin.zip" 2>&1 | grep -qF "droiddeck-session compares" \
    && ok "  ...and an empty minGlibc is called an off switch, not a default" \
    || fail "  ...and an empty minGlibc is called an off switch"
python3 "$HERE/check_archive.py" "$W/short.zip" 2>&1 \
    | grep -qF "meta.json declares libLLVM.so.19.1 and the archive does not carry it" \
    && ok "  ...and a promised companion the archive lacks is refused, named" \
    || fail "  ...and a promised companion the archive lacks is refused"
python3 "$HERE/check_archive.py" "$W/swap.zip" 2>&1 \
    | grep -qF "libraryName is 'libvulkan_zzz.so' and no such file is in the archive" \
    && ok "  ...and a libraryName pointing at nothing is refused, with the name it looked for" \
    || fail "  ...and a libraryName pointing at nothing is refused"

printf '| a | 1.0 MB, needs at most GLIBC_2.39 |\n| b | 2.0 MB, needs at most GLIBC_2.41 |\n' > "$W/rep"
python3 "$HERE/min_glibc.py" --report "$W/rep" --out "$W/min" >/dev/null 2>&1 \
    && [ "$(cat "$W/min" 2>/dev/null)" = "2.41" ] \
    && ok "min_glibc takes the highest requirement of the whole package" \
    || { fail "min_glibc takes the highest requirement"; cat "$W/min" 2>/dev/null; }
printf 'nothing useful here\n' > "$W/rep2"
if python3 "$HERE/min_glibc.py" --report "$W/rep2" --out "$W/min2" >"$W/o" 2>&1; then
    fail "a report with no numbers refuses instead of writing a blank minGlibc"
else
    ok "a report with no numbers refuses instead of writing a blank minGlibc"
fi

[ "$FAILED" = 0 ] && echo "lavapipe tool tests passed" || echo "LAVAPIPE TOOL TESTS FAILED"
exit "$FAILED"
