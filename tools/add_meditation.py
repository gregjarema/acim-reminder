#!/usr/bin/env python3
"""
Add (or refresh) the `meditation` field on every lesson in the shipped
app/src/main/assets/lessons.json, in place, without touching any other field.

The value is derived from each lesson's body by tools/meditation.py; see there
for what qualifies. Placed right after `phrase` in each record. Lessons with no
confident fuller form get "" and the app falls back to the phrase.

Usage:  python3 tools/add_meditation.py
"""
import importlib.util
import json
import os

HERE = os.path.dirname(__file__)
OUT = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "lessons.json"))


def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, filename))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


med = _load("med", "meditation.py")


def main():
    lessons = json.load(open(OUT, encoding="utf-8"))
    count = 0
    rebuilt = []
    for l in lessons:
        m = med.extract_meditation(l.get("body", ""), l.get("phrase", ""))
        if m:
            count += 1
        # Rebuild each record so `meditation` sits just after `phrase`.
        out = {}
        for k, v in l.items():
            out[k] = v
            if k == "phrase":
                out["meditation"] = m
        if "meditation" not in out:      # phrase somehow absent — still add it
            out["meditation"] = m
        rebuilt.append(out)

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(rebuilt, f, ensure_ascii=False, indent=2)

    print(f"Updated {len(rebuilt)} lessons; {count} now carry a fuller meditation verse.")


if __name__ == "__main__":
    main()
