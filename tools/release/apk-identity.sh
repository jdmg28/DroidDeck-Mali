#!/usr/bin/env bash
# The three things that decide whether one apk installs as an update of another, read out of the
# apk itself: its package name, its version code, and the SHA-256 of the certificate it is signed
# with. Printed as three words on one line, so a caller can compare two of them field by field.
#
#   tools/release/apk-identity.sh app/build/outputs/apk/debug/app-debug.apk
#   -> com.droiddeck.launcher.mali.g615 20 a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc
#
# An apk that does not verify is an error, not a line with an empty signature: apksigner exits
# non-zero and this script with it. When it cannot say, it says everything apksigner said, on
# stderr, because "found 0 certificates" on its own is not something anyone can act on.
set -euo pipefail
apk=${1:?usage: apk-identity.sh <apk>}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME or ANDROID_SDK_ROOT}}
tools=${BUILD_TOOLS:-$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)}
[ -f "$apk" ] || { echo "apk-identity: no such file: $apk" >&2; exit 1; }

package=$("$tools/aapt" dump badging "$apk" |
    sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)'.*/\1 \2/p" | head -1)
[ -n "$package" ] || { echo "apk-identity: $apk has no package line" >&2; exit 1; }

# Verified twice: once as the platform would (which accepts an apk signed with v1 only) and once
# demanding the v2 that a device running Android 8 or later checks for. An apk signed with v1 only
# still installs, and still updates from the same key, so its certificate is still worth reading -
# but the difference is worth knowing about, so it is reported.
said=$(mktemp)
trap 'rm -f "$said"' EXIT
if "$tools/apksigner" verify --print-certs "$apk" >"$said" 2>&1; then ok_any=1; else ok_any=0; fi
if "$tools/apksigner" verify --min-sdk-version 26 --print-certs "$apk" >>"$said" 2>&1; then ok26=1; else ok26=0; fi

# Every signer's certificate: one line each, so a single-signed apk gives one digest.
certs=$(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: \([0-9a-fA-F]\{64\}\).*/\1/p' "$said" | sort -u)
count=$(printf '%s\n' "$certs" | grep -c '[0-9a-fA-F]\{64\}' || true)
if [ "$count" != 1 ]; then
    {
        echo "apk-identity: expected exactly one signing certificate, found $count"
        echo "apksigner verify: exit $ok_any; with --min-sdk-version 26: exit $ok26"
        echo "--- apksigner said:"
        cat "$said"
    } >&2
    exit 1
fi
if [ "$ok26" != 1 ]; then
    echo "apk-identity: note: $apk has no v2 signature, so a device on Android 8 or later" \
         "would not accept it as a fresh install; its certificate is read all the same" >&2
fi
echo "$package $certs"
