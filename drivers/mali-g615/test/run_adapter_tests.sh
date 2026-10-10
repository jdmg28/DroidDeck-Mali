#!/usr/bin/env bash
# Runs the DroidDeck Mali adapter against a fake PanVK backend, on the build machine.
#
# The adapter is a pass-through with two additions, and both are things a phone is a bad way to
# test. It must not change what the backend sees (a pass-through that leaks is how a driver update
# becomes a game-breaking bug), and its trace must survive a device that dies mid-call - which by
# definition cannot be observed on the device, because the process and the phone go together.
#
# Needs: cc, and Vulkan headers. Set VULKAN_INCLUDE_DIR to a directory holding vulkan/vulkan_core.h
# and vulkan/vk_icd.h; the CI job clones them from KhronosGroup/Vulkan-Headers.
#
# Not part of the app or the driver package; nothing here is installed on the phone.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
ADAPTER_SRC="$ROOT/drivers/mali-g615/adapter/droiddeck_mali_icd.c"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for src in "$ADAPTER_SRC" "$HERE/mock_backend.c" "$HERE/test_adapter.c"; do
    [ -f "$src" ] || { echo "FAIL: missing $src"; exit 1; }
done
command -v cc >/dev/null 2>&1 || { echo "FAIL: no C compiler on this machine"; exit 1; }

VK_INC="${VULKAN_INCLUDE_DIR:-}"
if [ -z "$VK_INC" ]; then
    for guess in /usr/include /usr/local/include; do
        [ -f "$guess/vulkan/vulkan_core.h" ] && [ -f "$guess/vulkan/vk_icd.h" ] && VK_INC="$guess" && break
    done
fi
if [ -z "$VK_INC" ]; then
    echo "FAIL: no Vulkan headers (set VULKAN_INCLUDE_DIR to a dir with vulkan/vulkan_core.h)"
    exit 1
fi

# /dev/null is a char device, which is all the adapter's node lookup needs: it lets the test assert
# the real major/minor it reports instead of only that it reported something. The shipped build keeps
# the default /dev/mali0.
CFLAGS="-O1 -Wall -Wextra -std=gnu11 -fPIC -I$VK_INC -DDD_MALI_NODE=\"/dev/null\""
FAILED=0

# shellcheck disable=SC2086
cc $CFLAGS -shared -o "$WORK/libpanvk_backend.so" "$HERE/mock_backend.c" -ldl \
    || { echo "FAIL: the mock backend did not build"; exit 1; }
# shellcheck disable=SC2086
cc $CFLAGS -shared -fvisibility=hidden -o "$WORK/libvulkan_droiddeck_mali.so" "$ADAPTER_SRC" \
    -ldl -lpthread || { echo "FAIL: the adapter did not build"; exit 1; }
# shellcheck disable=SC2086
cc $CFLAGS -o "$WORK/test_adapter" "$HERE/test_adapter.c" -ldl \
    || { echo "FAIL: the adapter test did not build"; exit 1; }

run_case() {
    local label="$1"; shift
    local out status
    out=$("$@" 2>&1); status=$?
    if [ "$status" = 0 ]; then
        echo "  ok   $label"
    else
        echo "  FAIL $label (exit $status)"
        printf '%s\n' "$out" | sed 's/^/       /'
        FAILED=1
    fi
    LAST_OUT="$out"
}

echo "the adapter, against the fake backend:"

# 1. No DD_ADAPTER_TRACE: what the shipped driver does. Every backend pointer handed straight on,
# and no file anywhere.
out=$(cd "$WORK" && ./test_adapter ./libvulkan_droiddeck_mali.so ./libpanvk_backend.so 2>&1); status=$?
printf '%s\n' "$out" | sed 's/^/  /'
[ "$status" = 0 ] || FAILED=1
if ls "$WORK"/gpu-driver-trace.log >/dev/null 2>&1; then
    echo "  FAIL a session with no DD_ADAPTER_TRACE wrote a trace file"; FAILED=1
fi

# The backend must have been told about swapchain only: the DRM extension is the adapter's invention
# and a backend that never advertised it must not receive it.
if printf '%s\n' "$out" | grep -q 'vkCreateDevice enabled 1 extensions: VK_KHR_swapchain$'; then
    echo "  ok   the facade extension is added to the list and stripped before the backend sees it"
else
    echo "  FAIL the facade extension is added to the list and stripped before the backend sees it"
    printf '%s\n' "$out" | grep 'vkCreateDevice' | sed 's/^/       /'
    FAILED=1
fi

# 2. Tracing on: the calls the session made are in the file, with their results.
TRACE="$WORK/gpu-driver-trace.log"
run_case "a traced session passes the same checks" \
    env DD_ADAPTER_TRACE="$TRACE" DD_TEST_TRACE=1 \
    "$WORK/test_adapter" "$WORK/libvulkan_droiddeck_mali.so" "$WORK/libpanvk_backend.so"
missing=0
for want in '-> vkQueueSubmit' '<- vkQueueSubmit = 0' '-> vkWaitForFences' '<- vkWaitForFences = 2'; do
    grep -qF -- "$want" "$TRACE" || { echo "  FAIL the trace is missing: $want"; missing=1; }
done
if [ "$missing" = 0 ]; then
    echo "  ok   submit and an unfired fence are both recorded, entry and result"
fi
grep -qF -- '<- vkWaitForFences = 2' "$TRACE" && [ "$missing" = 0 ] && \
    echo "  ok   VK_TIMEOUT is recorded as the number the app will act on"
if [ "$missing" = 1 ]; then
    echo "       trace was:"
    sed 's/^/       /' "$TRACE" 2>/dev/null
    FAILED=1
fi

# 3. The reason it exists: the device dies in the middle of a call. The process is killed inside
# vkQueuePresentKHR, so the trace has to end with that entry and no exit - and it has to have
# survived the kill, which is what fdatasync per line buys.
HANG_TRACE="$WORK/hang.log"
env MOCK_HANG=1 DD_ADAPTER_TRACE="$HANG_TRACE" DD_TEST_TRACE=1 DD_TEST_HANG=1 \
    "$WORK/test_adapter" "$WORK/libvulkan_droiddeck_mali.so" "$WORK/libpanvk_backend.so" \
    >/dev/null 2>&1
last=$(tail -1 "$HANG_TRACE" 2>/dev/null)
if printf '%s' "$last" | grep -qF -- '-> vkQueuePresentKHR'; then
    echo "  ok   a process killed mid-present leaves the call it died in as the last line"
else
    echo "  FAIL a process killed mid-present leaves the call it died in as the last line"
    echo "       last line was: ${last:-<no file>}"
    FAILED=1
fi

if [ "$FAILED" = 0 ]; then
    echo "all adapter cases passed"
else
    echo "ADAPTER TESTS FAILED"
fi
exit "$FAILED"
