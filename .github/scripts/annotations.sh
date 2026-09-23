#!/usr/bin/env bash
# Usage: .github/scripts/annotations.sh <run-id>   — prints failing step + failure annotations.
run="$1"
job=$(gh run view "$run" --json jobs --jq '.jobs[0].databaseId')
gh run view "$run" --json jobs --jq '.jobs[0].steps[]|select(.conclusion=="failure")|"STEP FAILED: \(.name)"'
gh api --paginate "repos/boyk5031-sudo/Macro-Android/check-runs/$job/annotations" --jq '.[] | select(.annotation_level=="failure") | .message' | sort -t: -k1 -n
