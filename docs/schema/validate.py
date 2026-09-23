#!/usr/bin/env python3
"""Validate macro JSON documents against docs/schema/macro-v1.schema.json.

Usage: python3 docs/schema/validate.py [file.json ...]
Without arguments, validates every file in docs/schema/examples/ and runs the
built-in negative cases (documents that MUST be rejected).
Requires: pip install jsonschema
"""
import copy, json, sys, pathlib
from jsonschema import Draft202012Validator, FormatChecker

ROOT = pathlib.Path(__file__).resolve().parent
schema = json.loads((ROOT / "macro-v1.schema.json").read_text())
Draft202012Validator.check_schema(schema)
V = Draft202012Validator(schema, format_checker=FormatChecker())

def errors(doc):
    return sorted(V.iter_errors(doc), key=lambda e: list(e.path))

def main(argv):
    ok = True
    files = [pathlib.Path(a) for a in argv] or sorted((ROOT / "examples").glob("*.json"))
    for f in files:
        errs = errors(json.loads(f.read_text()))
        print(f"{'OK  ' if not errs else 'FAIL'} {f}")
        for e in errs[:10]:
            print(f"      {'/'.join(map(str, e.path)) or '<root>'}: {e.message[:140]}")
        ok &= not errs
    if argv:
        return 0 if ok else 1

    base = json.loads((ROOT / "examples" / "open-wifi-settings.json").read_text())
    S = "0a0a0a0a-0000-4000-8000-0000000000ff"
    def step(action, **extra):
        return lambda d: d["macros"][0]["steps"].__setitem__(0, {"id": S, "action": action, **extra})
    negatives = {
        "unknown top-level key": lambda d: d.__setitem__("extra", 1),
        "schemaVersion 2": lambda d: d.__setitem__("schemaVersion", 2),
        "reserved action screenshot": step({"type": "screenshot"}),
        "repeat count 101": step({"type": "repeat", "count": 101, "body": []}),
        "repeat count and whileCondition": step({"type": "repeat", "count": 1, "maxIterations": 1, "body": [],
                                                 "whileCondition": {"type": "appInstalled", "packageName": "a.b"}}),
        "selector without matching field": step({"type": "clickNode", "selector": {"index": 0}}),
        "clickNode requireVisible=false": step({"type": "clickNode", "selector": {"text": "x"}, "requireVisible": False}),
        "package name without dot": step({"type": "launchApp", "packageName": "nodots"}),
        "parallel with one child": step({"type": "parallel", "children": [
            {"id": "0a0a0a0a-0000-4000-8000-0000000000fe", "action": {"type": "wait", "duration": "PT1S"}}]}),
        "continueOnCancel=true": lambda d: d["macros"][0]["steps"][0].__setitem__("continueOnCancel", True),
        "interval below 15 minutes": lambda d: d["schedules"][0].__setitem__("kind", {"type": "interval", "minutes": 10}),
        "localTime 25:00": lambda d: d["schedules"][0]["kind"].__setitem__("localTime", "25:00"),
        "variable name with dash": lambda d: d["macros"][0]["variables"].__setitem__("bad-name", {"type": "int", "value": 1}),
        "empty steps": lambda d: d["macros"][0].__setitem__("steps", []),
        "non-ISO duration": step({"type": "wait", "duration": "10s"}),
        "openUrl non-http scheme": step({"type": "openUrl", "url": {"type": "literal", "text": "intent://x"}}),
        "enterText with unknown field": step({"type": "enterText", "text": {"type": "literal", "text": "x"}, "password": True}),
    }
    positives = {
        "enterText without selector (focused editable)": step({"type": "enterText", "text": {"type": "literal", "text": "x"}}),
        "openUrl from variable": step({"type": "openUrl", "url": {"type": "var", "name": "target"}}),
    }
    for name, mutate in positives.items():
        d = copy.deepcopy(base); mutate(d)
        accepted = not errors(d)
        print(f"{'OK  ' if accepted else 'FAIL'} accepts: {name}")
        ok &= accepted
    for name, mutate in negatives.items():
        d = copy.deepcopy(base); mutate(d)
        rejected = bool(errors(d))
        print(f"{'OK  ' if rejected else 'FAIL'} rejects: {name}")
        ok &= rejected
    print("ALL PASSED" if ok else "FAILURES")
    return 0 if ok else 1

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
