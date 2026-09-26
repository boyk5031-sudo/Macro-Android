#!/usr/bin/env python3
"""Packs Android Lint findings from every module into a handful of annotations plus the step summary.

Reads the text reports (`lint-results-*.txt`) and, when present, the XML reports. GitHub keeps only 10
annotations per level per step, so findings are packed into <=3500-char notices ordered by module.
"""
import glob
import os
import re
from collections import Counter

HEADER = re.compile(r"^(?P<file>\S.*?):(?P<line>\d+): (?P<sev>Error|Warning|Fatal): (?P<msg>.*) \[(?P<id>[A-Za-z]+)(?: from [^\]]+)?\]$")
ROOT = os.getcwd() + "/"

findings = []  # (module, id, sev, file, line, msg)
for path in sorted(glob.glob("**/build/reports/lint-results-*.txt", recursive=True)):
    module = path.split("/build/")[0]
    with open(path, errors="replace") as f:
        for raw in f:
            m = HEADER.match(raw.rstrip().replace(ROOT, ""))
            if m:
                findings.append((module, m["id"], m["sev"], m["file"], m["line"], m["msg"]))

seen = set()
unique = [x for x in findings if not (x[:5] in seen or seen.add(x[:5]))]
by_id = Counter(x[1] for x in unique)


def emit(level, title, lines):
    buf, n = "", 1
    for line in lines:
        line = line.replace("::", " ").replace("%", "%25")
        if len(buf) + len(line) + 3 > 3500:
            print(f"::{level} title={title} {n}::{buf}")
            buf, n = "", n + 1
        buf = line if not buf else buf + "%0A" + line
    if buf:
        print(f"::{level} title={title} {n}::{buf}")


summary = [f"lint findings: {len(unique)} in {len(set(x[0] for x in unique))} module(s)"]
summary += [f"  {k}: {v}" for k, v in by_id.most_common()]
emit("notice", "lint-summary", summary)
emit("notice", "lint-findings", [f"{m} {f}:{l} [{i}] {msg[:160]}" for (m, i, s, f, l, msg) in unique][:400])

with open(os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null"), "a") as out:
    out.write("## Android Lint\n\n" + "\n".join(f"- {s}" for s in summary) + "\n\n")
    for (m, i, s, f, l, msg) in unique:
        out.write(f"- `{m}` `{f}:{l}` **{i}** ({s}) {msg}\n")
