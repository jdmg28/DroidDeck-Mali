#!/usr/bin/env bash
# Tests for the software-mode part of droiddeck-session - run for real, in a shell, against fixtures.
#
# The session script as a whole cannot be run here: it wants the guest rootfs, proot, gamescope and a
# display. But the branch that decides whether a software-mode session starts or refuses is pure shell
# over three files and two variables, so it can be cut out of the script and executed. That is worth the
# extraction because the failure this branch exists to prevent is a *silent* one: an unreadable driver
# that falls back to the runtime's own ICD would put the GPU back into a session that promised it
# wouldn't, and only the phone would notice.
#
# The markers below are the extraction contract. If the script is ever rearranged, this test fails with
# "the script moved" rather than quietly testing nothing - which is the only way a structural test can
# be trusted.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../linuxfs/overlay/usr/local/bin/droiddeck-session"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FAILED=0
ok()   { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; FAILED=1; }

# ---- extract the block ----------------------------------------------------------------------------
python3 - "$SCRIPT" "$W/block.sh" <<'PY'
import sys
src, out = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8", errors="replace").read()
start = text.find("dd_at_least() {")
begin = text.find('if [ "${BL_SOFTWARE_VULKAN:-}" = 1 ]; then')
end = text.find('if [ "${BL_GPU_FAMILY:-}" = mali ] && [ "${BL_SOFTWARE_VULKAN:-}" != 1 ]'
               ' && [ -z "${BL_VK_DRIVER:-}" ]')
if min(start, begin) < 0 or end < 0 or not (start < begin < end):
    sys.exit("the software-mode block is not where this test expects it in droiddeck-session: "
             "markers at %s/%s/%s. Update the test with the script, never by relaxing the assertion."
             % (start, begin, end))
body = text[start:end].rstrip() + '\n'
# What the script would have done next, so the test can see the effect rather than only the message.
# The probe reports whether the loader was pointed anywhere, as a single token: echoing the value
# itself would print "none" for the cases where the block correctly did nothing, and a test that
# passes on both is not a test.
body += 'echo "PROBE exported=${VK_DRIVER_FILES+yes}${VK_ICD_FILENAMES+yes} libpath=${LD_LIBRARY_PATH:-unset}"\nexit 0\n'
open(out, "w", encoding="utf-8").write(
    "set -u\n# extracted from droiddeck-session for testing; see the header of this test\n" + body)
PY
[ -f "$W/block.sh" ] || { echo "extraction failed"; exit 1; }

# A guest whose getconf says what we tell it, and one that says nothing at all.
make_getconf() {
  mkdir -p "$W/$1/bin"
  if [ "$2" = none ]; then printf '#!/bin/sh\nexit 1\n' > "$W/$1/bin/getconf"
  else printf '#!/bin/sh\necho "glibc %s"\n' "$2" > "$W/$1/bin/getconf"; fi
  chmod +x "$W/$1/bin/getconf"
}
make_getconf g239 2.39
make_getconf g228 2.28
make_getconf gnone none

# A driver directory in the shape the importer leaves it: icd.json naming its library beside itself.
make_driver() {
  local d="$W/$1"
  mkdir -p "$d"
  : > "$d/libvulkan_lvp.so"
  printf '{\n    "file_format_version": "1.0.1",\n    "ICD": {\n        "library_path": "\\/%s\\/libvulkan_lvp.so",\n        "api_version": "1.3.204"\n    }\n}\n' \
    "${d#/}" > "$d/icd.json"
  echo "$d"
}
DRV=$(make_driver driver)
BROKEN=$(make_driver broken)
rm -f "$BROKEN/libvulkan_lvp.so"

# run <label> <getconf-dir> <expect-exit> <expect-substring...> with BL_* in the environment
run() {
  local label="$1" conf="$2" want="$3"; shift 3
  local out status
  out=$(PATH="$W/$conf/bin:$PATH" \
        BL_SOFTWARE_VULKAN="${SOFT:-1}" BL_VK_DRIVER="${ICD-$DRV/icd.json}" \
        BL_DRIVER_MIN_GLIBC="${NEED:-}" \
        bash "$W/block.sh" 2>&1); status=$?
  if [ "$status" != "$want" ]; then
    fail "$label (exit $status, wanted $want)"; printf '%s\n' "$out" | sed 's/^/       /'; return
  fi
  local bad=0
  for needle in "$@"; do
    # A leading ! means "must not appear", which is how the refusals are proved: not that they said
    # something, but that the export they were refusing never happened.
    case $needle in
      \!*) printf '%s\n' "$out" | grep -qF -- "${needle#!}" \
             && { bad=1; echo "       must not appear: ${needle#!}"; } ;;
      *)   printf '%s\n' "$out" | grep -qF -- "$needle" \
             || { bad=1; echo "       missing: $needle"; } ;;
    esac
  done
  if [ "$bad" = 0 ]; then ok "$label"; else FAILED=1; printf '%s\n' "$out" | sed 's/^/       /'; fi
}

# ---- the cases ------------------------------------------------------------------------------------
NEED=2.38 run "a rootfs new enough for the driver starts, naming both versions" g239 0 \
    "== vulkan driver: software only" "glibc 2.39 is at least the 2.38 the driver was built for" \
    "PROBE exported=yesyes libpath=$DRV"
# The loader is pointed at exactly the manifest we handed in - that equality is what keeps the
# runtime's own PanVK manifest out of the enumeration, so it is asserted, not assumed.
NEED=2.50 run "a rootfs too old is refused before anything is exported" g228 78 \
    "DD_SOFTWARE_GLIBC_OLD" "glibc 2.28" "built for 2.50"
# The probe line the extracted block prints at its end is absent here for a reason: the refusal returns
# before any of the three exports. Asserting the driver directory appears nowhere in the output is the
# same claim from the other side - a run that got that far would name it in the "== vulkan driver" line.
NEED=2.50 run "  ...and the refusal happens before the loader is pointed anywhere" g228 78 \
    "!exported=yes" "!$DRV"
SOFT=0 run "software mode off means this branch does not run at all" g239 0 "!software only" "!exported=yes"
NEED= run "a package that declares no minimum glibc says so and continues" g239 0 \
    "no minimum glibc declared"
ICD=$DRV/icd.json NEED=2.39 run "equal versions are enough (>=, not >)" g239 0 "at least the 2.39"
ICD=$BROKEN/icd.json NEED=2.38 run "an unreadable library is refused, not fallen back from" g239 78 \
    "DD_SOFTWARE_DRIVER_MISSING"
ICD=$BROKEN/icd.json NEED=2.38 run "  ...even when the rootfs glibc is fine, and says what to import" g239 78 \
    "libvulkan_lvp.so"
ICD= NEED=2.38 run "no driver at all is a refusal too, in every ordering" g239 78 \
    "DD_SOFTWARE_DRIVER_MISSING"
ICD=$DRV/icd.json NEED=2.38 run "a rootfs that reports no glibc is a note, not a refusal" gnone 0 \
    "reported none" "software only" "!DD_SOFTWARE_GLIBC_OLD"
ICD=$DRV/icd.json NEED=2.38 run "and a musl-style runtime still gets the driver exported" gnone 0 \
    "PROBE exported=yesyes"

# LD_LIBRARY_PATH is prepended, never replaced, and never left with a trailing ":" - glibc reads an empty
# component as "this directory", which is a search path entry nobody intended to add.
run_path() {
  local label="$1" give="$2" want="$3" out
  out=$(PATH="$W/g239/bin:$PATH" BL_SOFTWARE_VULKAN=1 BL_VK_DRIVER="$DRV/icd.json" \
        BL_DRIVER_MIN_GLIBC=2.38 LD_LIBRARY_PATH="$give" bash "$W/block.sh" 2>&1)
  printf '%s\n' "$out" | grep -qF -- "libpath=$want" && ok "$label" \
    || { fail "$label"; printf '%s\n' "$out" | sed 's/^/       /'; }
}
run_path "the companion directory goes first in LD_LIBRARY_PATH" "/already/here" "$DRV:/already/here"
run_path "and an unset path does not grow a trailing separator" "" "$DRV"

[ "$FAILED" = 0 ] && echo "all software-mode session cases passed" || echo "SOFTWARE-MODE SESSION TESTS FAILED"
exit "$FAILED"
