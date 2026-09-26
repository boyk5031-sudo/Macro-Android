#!/usr/bin/env python3
"""Turns every Android Lint XML report into GitHub annotations (one per issue) plus a step summary."""
import glob
import os
import xml.etree.ElementTree as ET

issues = []
for path in glob.glob("**/build/reports/lint-results-*.xml", recursive=True):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    module = path.split("/build/")[0]
    for issue in root.findall("issue"):
        loc = issue.find("location")
        file = loc.get("file", "?") if loc is not None else "?"
        line = loc.get("line", "0") if loc is not None else "0"
        issues.append((issue.get("severity", "?"), issue.get("id", "?"), module, file, line, issue.get("message", "")))

by_id = {}
for sev, iid, module, file, line, msg in issues:
    by_id[iid] = by_id.get(iid, 0) + 1
    level = "error" if sev in ("Error", "Fatal") else "warning"
    short = os.path.relpath(file) if os.path.isabs(file) else file
    print(f"::{level} title=lint {iid}::{module}: {short}:{line} {' '.join(msg.split())[:500]}")

print(f"::notice title=lint total={len(issues)}::" + ", ".join(f"{k}={v}" for k, v in sorted(by_id.items())))
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as out:
        out.write(f"## Lint\n\n{len(issues)} issue(s)\n\n")
        for sev, iid, module, file, line, msg in issues[:200]:
            out.write(f"- **{iid}** ({sev}) `{module}` {file}:{line} — {msg}\n")
