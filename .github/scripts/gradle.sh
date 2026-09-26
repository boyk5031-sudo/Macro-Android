#!/usr/bin/env bash
# Runs a Gradle invocation, tees output to build/ci-logs/<name>.log, and surfaces the failure diagnostics as a
# small number of PACKED GitHub annotations (GitHub keeps only 10 per level per step and 50 per job, and the
# hosted log/artifact APIs are not always reachable from where we read results).
set -uo pipefail
name="$1"; shift
mkdir -p build/ci-logs
log="build/ci-logs/${name}.log"
./gradlew "$@" --stacktrace --no-daemon --console=plain 2>&1 | tee "$log"
status=${PIPESTATUS[0]}

# Emit one annotation per <=3500-char chunk of stdin; newlines become %0A so the block stays readable.
pack() { # level title
  awk -v level="$1" -v title="$2" '
    { gsub(/::/, " ", $0); gsub(/%/, "%25", $0); line=$0
      if (length(buf) + length(line) + 3 > 3500) { printf "::%s title=%s %d::%s\n", level, title, n, buf; buf=""; n++ }
      buf = (buf == "" ? line : buf "%0A" line) }
    BEGIN { n = 1 }
    END { if (buf != "") printf "::%s title=%s %d::%s\n", level, title, n, buf }'
}

if [ "$status" -ne 0 ]; then
  # 1. Kotlin compiler diagnostics (errors and, because -Werror is on, warnings).
  grep -E '^(e|w): ' "$log" | sed -E 's#file:///home/runner/work/[^/]+/[^/]+/##' | sort -u | head -120 \
    | pack error "$name-kotlin"
  # 2. The authoritative "What went wrong" block(s), without JVM stack frames.
  awk '/^FAILURE:|^\* What went wrong/{p=1} /^\* Try:/{p=0} p' "$log" | grep -vE '^\s+at ' | head -120 \
    | pack error "$name-failure"
  # 3. Failing test names as printed by Gradle.
  grep -E '^[A-Za-z0-9_.]+ > .* FAILED$|^ +[A-Za-z0-9_.`]+ > .* FAILED$' "$log" | head -60 | pack error "$name-tests"
  # 4. Other error-ish lines (deduplicated) as a low-priority notice.
  grep -E 'error:|Error:|Could not|Unresolved|Execution failed|problems? (were|was) found|DSL element|Cannot' "$log" \
    | grep -vE 'at org\.|at java\.|at kotlin\.|UP-TO-DATE|FROM-CACHE|NO-SOURCE' | sort -u | head -60 \
    | pack notice "$name-misc"
fi
exit "$status"
