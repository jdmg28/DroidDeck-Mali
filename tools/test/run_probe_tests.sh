#!/usr/bin/env bash
# Exercises droiddeck-gpu-probe against a fake Vulkan loader, on the build machine.
#
# The probe is the one part of the guard that has to be right before it is needed: it is what
# decides whether Steam is allowed to touch the GPU at all. It shipped twice with bugs that only
# showed up on the phone - first an AttributeError inside its own error path, then a NULL from the
# loader it was questioning wrongly - and both times the failure looked like a "skip", which the
# app lets through. A GPU-less machine can still prove the whole thing, because the only Vulkan
# the probe needs is a loader that answers like one.
#
# Requires gcc and python3. Nothing here is installed on the phone.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SCRIPT="$ROOT/tools/linuxfs/overlay/usr/local/bin/droiddeck-gpu-probe"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ ! -f "$SCRIPT" ]; then
    echo "FAIL: no probe at $SCRIPT"
    exit 1
fi

# The python is a heredoc inside the shell script; lift it out so it can be run directly.
awk "/<<'PY'/{flag=1;next} /^PY\$/{flag=0} flag" "$SCRIPT" > "$WORK/probe.py"
if [ ! -s "$WORK/probe.py" ]; then
    echo "FAIL: could not extract the probe's python from $SCRIPT"
    exit 1
fi
if python3 -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' "$WORK/probe.py" 2>"$WORK/syntax.log"; then
    echo "probe python: $(wc -l < "$WORK/probe.py") lines, parses"
else
    echo "FAIL: the extracted probe python does not parse"
    cat "$WORK/syntax.log"
    echo "--- first 5 and last 5 extracted lines ---"
    head -5 "$WORK/probe.py"
    echo "..."
    tail -5 "$WORK/probe.py"
    exit 1
fi

for tool in gcc python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: $tool is not on this machine"; exit 1; }
done
echo "machine: $(uname -sr)  gcc: $(gcc -dumpversion)  python: $(python3 -V 2>&1)"

if ! gcc -shared -fPIC -o "$WORK/libvulkan.so.1" "$HERE/mock_vulkan.c" 2>"$WORK/gcc.log"; then
    echo "FAIL: the fake loader did not build"
    cat "$WORK/gcc.log"
    exit 1
fi

FAILED=0

# case: name, environment, the verdict expected, the exit code expected
check() {
    local name="$1" env="$2" want_verdict="$3" want_exit="$4"
    local out status
    out=$(env LD_LIBRARY_PATH="$WORK" $env python3 "$WORK/probe.py" /nonexistent/icd.json 2>&1)
    status=$?
    if printf '%s\n' "$out" | grep -q "$want_verdict" && [ "$status" = "$want_exit" ]; then
        echo "  ok   $name"
    else
        echo "  FAIL $name"
        echo "       wanted: $want_verdict (exit $want_exit)"
        echo "       got:    $(printf '%s\n' "$out" | grep -E '^DD_GPU_PROBE_' || echo '(no verdict)') (exit $status)"
        FAILED=1
    fi
}

# The probe declares its Vulkan structs by hand, because the guest has no headers and no compiler.
# That is how a wrong layout survives review: VkPhysicalDeviceProperties has no sType/pNext, unlike
# nearly every other Vulkan struct, and one revision of this probe "corrected" that omission, which
# shifted every field 16 bytes and made the device name read the driver version. The fake loader
# below could not catch it, because it was written from the same assumption - and a mock that repeats
# a mistake proves nothing. So where real headers are within reach, the ABI gets the last word.
VK_INC="${VULKAN_INCLUDE_DIR:-}"
if [ -z "$VK_INC" ]; then
    for guess in /usr/include /usr/local/include; do
        [ -f "$guess/vulkan/vulkan_core.h" ] && VK_INC="$guess" && break
    done
fi

if [ -n "$VK_INC" ] && [ -f "$HERE/check_probe_layout.py" ]; then
    if cc -O0 -I"$VK_INC" -o "$WORK/vulkan_offsets" "$HERE/vulkan_offsets.c" 2>"$WORK/offsets-build.log"; then
        python3 "$HERE/check_probe_layout.py" "$WORK/vulkan_offsets" "$WORK/probe.py" || FAILED=1
    else
        echo "  FAIL the ABI checker did not build; the probe's struct layout went unverified"
        sed 's/^/       /' "$WORK/offsets-build.log" | head -10
        FAILED=1
    fi
elif [ -z "$VK_INC" ]; then
    echo "  (no Vulkan headers found: the struct layout check is skipped. CI runs it with"
    echo "   VULKAN_INCLUDE_DIR pointing at KhronosGroup/Vulkan-Headers - see gpu-guard-ci.yml)"
fi

echo "droiddeck-gpu-probe against the fake loader:"
check "a working GPU passes and names itself"  ""                    "DD_GPU_PROBE_OK: "              0
check "the device is reported as the Mali-G615" ""                   "Mali-G615 MC6"                  0
check "a fence that never signals blocks"       "MOCK_FENCE_RESULT=2" "DD_GPU_PROBE_SUBMIT_TIMEOUT: "  78
check "a fence that lies about the work blocks" "MOCK_SKIP_FILL=1"     "DD_GPU_PROBE_FAILED: "          78

# A loader that was asked for by name and is not there. Deterministic, unlike "no loader at all":
# ubuntu-24.04 runners ship a system libvulkan.so.1, so a case built on its absence passed at home
# and did the opposite here - which is the whole reason the loader is selectable by name.
out=$(env DD_GPU_PROBE_LOADER="$WORK/nope.so" python3 "$WORK/probe.py" /nonexistent/icd.json 2>&1); status=$?
if printf '%s\n' "$out" | grep -q "DD_GPU_PROBE_LOADER=.*cannot be opened" && [ "$status" = 0 ]; then
    echo "  ok   a loader named and missing is named back, as a probe that could not run"
else
    echo "  FAIL a loader named and missing is named back, as a probe that could not run (exit $status)"
    printf '%s\n' "$out" | tail -3 | sed 's/^/       /'
    FAILED=1
fi

# No driver manifest. On a machine with a loader the loader answers and finds nothing behind it;
# on a machine without one the probe reports a skip. Either way it must not claim the GPU works,
# and only those two answers are allowed - anything else is an unhandled death.
out=$(env LD_LIBRARY_PATH="$WORK/empty" python3 "$WORK/probe.py" "$WORK/absent.json" 2>&1); status=$?
if ! printf '%s\n' "$out" | grep -q "DD_GPU_PROBE_OK" && { [ "$status" = 0 ] || [ "$status" = 78 ]; }; then
    echo "  ok   no driver present never reads as a pass"
else
    echo "  FAIL no driver present never reads as a pass (exit $status)"
    printf '%s\n' "$out" | tail -3 | sed 's/^/       /'
    FAILED=1
fi

if [ "$FAILED" = 0 ]; then
    echo "all probe cases passed"
else
    echo "PROBE TESTS FAILED"
fi
exit "$FAILED"
