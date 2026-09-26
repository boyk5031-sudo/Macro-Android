#!/usr/bin/env python3
"""Prints detekt's checkstyle XML as GitHub annotations + a step summary grouped by rule.

GitHub keeps at most 10 annotations per level per step, each up to ~4 KB, so the report is packed:
  * 1 notice with the per-rule counts,
  * up to 10 warnings: one per rule (most frequent first) with its first finding,
  * up to 9 notices packing every finding as `rule path:line` (≈ 3.5 KB each) so the whole report is
    readable from the checks API even when logs/artifacts are not reachable.
"""
import collections
import os
import sys
import xml.etree.ElementTree as ET

MAX_CHUNK = 3500

path = sys.argv[1]
if not os.path.exists(path):
    print(f"::notice title=detekt::no report at {path}")
    sys.exit(0)

root = ET.parse(path).getroot()
by_rule = collections.Counter()
examples = collections.defaultdict(list)
total = 0
for f in root.findall("file"):
    name = f.get("name")
    rel = name.split("/Macro-Android/", 1)[-1]
    for e in f.findall("error"):
        total += 1
        rule = e.get("source", "?").split(".")[-1]
        by_rule[rule] += 1
        msg = e.get("message", "").replace("\n", " ")
        examples[rule].append((rel, e.get("line"), msg))

counts = ", ".join(f"{rule}={n}" for rule, n in by_rule.most_common())
print(f"::notice title=detekt total={total}::{counts[:3900]}")

for rule, n in by_rule.most_common(10):
    rel, line, msg = examples[rule][0]
    print(f"::warning title=detekt {rule} ({n})::{rel}:{line} {msg[:600]}")

# Pack all findings (rule + location, no message) into notice chunks.
lines = []
for rule, _ in by_rule.most_common():
    for rel, line, _ in examples[rule]:
        short = rel.replace("src/main/kotlin/com/macroandroid/", "…/").replace("src/test/kotlin/com/macroandroid/", "…test/")
        lines.append(f"{rule} {short}:{line}")
chunks, cur = [], ""
for item in lines:
    if len(cur) + len(item) + 3 > MAX_CHUNK:
        chunks.append(cur)
        cur = ""
    cur += item + " | "
if cur:
    chunks.append(cur)
for i, chunk in enumerate(chunks[:9]):
    print(f"::notice title=detekt findings {i + 1}/{len(chunks)}::{chunk}")

with open(os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null"), "a") as out:
    out.write(f"## detekt: {total} issues\n\n| rule | count |\n|---|---|\n")
    for rule, n in by_rule.most_common():
        out.write(f"| {rule} | {n} |\n")
    out.write("\n<details><summary>All findings</summary>\n\n")
    for rule, _ in by_rule.most_common():
        for rel, line, msg in examples[rule]:
            out.write(f"- `{rule}` {rel}:{line} {msg}\n")
    out.write("\n</details>\n")
