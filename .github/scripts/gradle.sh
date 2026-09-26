#!/usr/bin/env bash
# Runs a Gradle invocation, tees output to build/ci-logs/<name>.log, and surfaces the
# failure diagnostics as GitHub annotations (the hosted log API is not always reachable).
set -uo pipefail
name="$1"; shift
mkdir -p build/ci-logs
log="build/ci-logs/${name}.log"
./gradlew "$@" --stacktrace --no-daemon --console=plain 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  # Kotlin compiler diagnostics first (errors and, because -Werror is on, warnings). GitHub keeps only the first
  # 10 annotations per level per step, so they are packed into single notice annotations of <=3500 chars each.
  grep -E '^(e|w): ' "$log" | sed -E 's#file:///home/runner/work/[^/]+/[^/]+/##' | sort -u | head -80 \
    | awk 'BEGIN{buf="";n=1} {line=$0; gsub(/::/," ",line); if (length(buf)+length(line)+3>3500){printf "::notice title=kotlin-diagnostics %d::%s\n",n,buf; buf="";n++} buf=(buf==""?line:buf " | " line)} END{if(buf!="")printf "::notice title=kotlin-diagnostics %d::%s\n",n,buf}'
  # Compiler errors / warnings-as-errors / configuration problems.
  grep -nE '^e: |error:|Error:|FAILURE:|What went wrong|Caused by:|\* Exception is|Could not|Unresolved|Execution failed|> Task .* FAILED|Failed to|has been compiled|problems? (were|was) found|compileDebugKotlin|Unknown Kotlin JVM target|DSL element|Expecting|Cannot|failed;' "$log" \
    | grep -vE 'Caused by: org.gradle|at org\.|at java\.|at kotlin\.' \
    | head -60 | while IFS= read -r line; do
        # sanitise :: for annotation syntax
        printf '::error title=%s::%s\n' "$name" "${line//::/ }"
      done
  # Failing test names.
  grep -nE 'FAILED$|^ +[A-Za-z0-9_.]+ > .* FAILED' "$log" | head -40 | while IFS= read -r line; do
    printf '::error title=%s-test::%s\n' "$name" "${line//::/ }"
  done
fi
exit "$status"
