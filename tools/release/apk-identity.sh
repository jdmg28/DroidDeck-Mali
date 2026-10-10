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

# apksigner prints the certificates whether or not it is happy, and its happiness depends on the
# sdk range it is asked about: an apk carrying only a v3 signature fails a min-sdk-version 26 check
# because v3 is not read before Android 9. The certificate is the identity here, so it is read from
# both runs and the complaints are reported rather than obeyed.
said=$(mktemp)
trap 'rm -f "$said"' EXIT
"$tools/apksigner" verify --print-certs "$apk" >"$said" 2>&1 || true
"$tools/apksigner" verify --min-sdk-version 28 --print-certs "$apk" >>"$said" 2>&1 || true

# Every signer's certificate: one line each, so a single-signed apk gives one digest. apksigner
# labels them "Signer #1" or "V3.0 Signer" depending on what it found.
certs=$(sed -n 's/^.*certificate SHA-256 digest: \([0-9a-fA-F]\{64\}\).*/\1/p' "$said" | sort -u)
count=$(printf '%s\n' "$certs" | grep -c '[0-9a-fA-F]\{64\}' || true)
if [ "$count" != 1 ]; then
    {
        echo "apk-identity: expected exactly one signing certificate, found $count"
        echo "apk-identity: $apk is \`$package\`"
        echo "--- apksigner said:"
        cat "$said"
    } >&2
    exit 1
fi
# Whatever apksigner objects to, it is worth knowing about even when the certificate is readable.
sed -n 's/^\(WARNING\|ERROR\): /apk-identity note: /p' "$said" | sort -u >&2 || true
echo "$package $certs"
