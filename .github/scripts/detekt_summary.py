#!/usr/bin/env python3
"""Prints detekt's checkstyle XML as GitHub annotations + a step summary grouped by rule."""
import collections, os, sys, xml.etree.ElementTree as ET

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
        examples[rule].append(f"{rel}:{e.get('line')}:{e.get('column')} {msg}")
print(f"::notice title=detekt::total issues={total}")
for rule, n in by_rule.most_common():
    for ex in examples[rule][:6]:
        print(f"::warning title=detekt {rule} ({n})::{ex[:900]}")
with open(os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null"), "a") as out:
    out.write(f"## detekt: {total} issues\n\n| rule | count |\n|---|---|\n")
    for rule, n in by_rule.most_common():
        out.write(f"| {rule} | {n} |\n")
    out.write("\n<details><summary>All findings</summary>\n\n")
    for rule, _ in by_rule.most_common():
        for ex in examples[rule]:
            out.write(f"- `{rule}` {ex}\n")
    out.write("\n</details>\n")
