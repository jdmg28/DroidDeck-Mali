#!/usr/bin/env bash
#
# Runs a script and puts what it printed into report.md as well, when it fails.
#
# A GitHub Actions step's log is not readable from everywhere, and a refusal that only exists there
# is a refusal nobody can act on. The workflows here keep a report.md as they go and publish it to
# a release's notes; this puts a failing step's own words in it.
#
#   bash tools/ci/with-report.sh "<step name>" <script>
#
# The script's output still goes to the step's log. The exit status is the script's.

set -uo pipefail

name=${1:?usage: with-report.sh <name> <script>}
script=${2:?usage: with-report.sh <name> <script>}

out=$(bash "$script" 2>&1)
status=$?

printf '%s\n' "$out"

if [ "$status" != 0 ]; then
    {
        echo
        echo "### $name failed (exit $status)"
        echo '```'
        printf '%s\n' "$out" | tail -80
        echo '```'
    } >> report.md
    echo "::error::$name failed (see the report)"
fi

exit "$status"
