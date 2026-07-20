#!/usr/bin/env python3
"""
Assemble the final app/src/main/assets/lessons.json.

Sources:
  - Lessons 98-114: the existing lessons.json (clean digest data).
  - Lessons 115+  : parsed from the emails in emails/.

We ship the largest CONTIGUOUS run starting at lesson 98 (so the daily schedule
never hits a gap). The app cycles through these in order (see Lessons.java).
Whatever emails exist beyond the contiguous run are ignored for now; rerun this
after fetching more to extend the run.

Usage: python3 tools/finalize_lessons.py
"""
import glob
import importlib.util
import json
import os

HERE = os.path.dirname(__file__)
OUT = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "lessons.json"))
EMAILS = os.path.abspath(os.path.join(HERE, "..", "emails"))

spec = importlib.util.spec_from_file_location("b", os.path.join(HERE, "build_lessons_from_email.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

START = 98

# Base: existing lessons.json (digest data for 98-114).
base = {}
if os.path.exists(OUT):
    for l in json.load(open(OUT, encoding="utf-8")):
        base[l["number"]] = l

# Emails: everything valid.
emailed = {}
for f in glob.glob(os.path.join(EMAILS, "lesson*.html")):
    d = b.extract(f)
    if d["phrase"] and d["body"] and d["video"]:
        emailed[d["number"]] = d

covered = set(base) | set(emailed)

# Largest contiguous run from START.
n = START
while n in covered:
    n += 1
last = n - 1

out = []
for num in range(START, last + 1):
    # Prefer the clean digest entry where we have one, else the email parse.
    out.append(base.get(num) if num in base else emailed[num])

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)

print(f"Shipped contiguous lessons {START}..{last}  ({len(out)} lessons)")
extra = sorted(k for k in emailed if k > last)
if extra:
    print(f"(on disk but beyond the contiguous run, not shipped yet: {len(extra)} lessons, "
          f"first few: {extra[:10]})")
