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

echo "droiddeck-gpu-probe against the fake loader:"
check "a working GPU passes and names itself"  ""                    "DD_GPU_PROBE_OK: "              0
check "the device is reported as the Mali-G615" ""                   "Mali-G615 MC6"                  0
check "a fence that never signals blocks"       "MOCK_FENCE_RESULT=2" "DD_GPU_PROBE_SUBMIT_TIMEOUT: "  78
check "a fence that lies about the work blocks" "MOCK_SKIP_FILL=1"     "DD_GPU_PROBE_FAILED: "          78

# No loader at all: the probe must report a skip and must never block the launch.
out=$(python3 "$WORK/probe.py" /nonexistent/icd.json 2>&1); status=$?
if printf '%s\n' "$out" | grep -q "DD_GPU_PROBE_SKIPPED: no Vulkan loader" && [ "$status" = 0 ]; then
    echo "  ok   no loader is a skip, and a skip never blocks"
else
    echo "  FAIL no loader is a skip, and a skip never blocks (exit $status)"
    FAILED=1
fi

if [ "$FAILED" = 0 ]; then
    echo "all probe cases passed"
else
    echo "PROBE TESTS FAILED"
fi
exit "$FAILED"
