#!/usr/bin/env python3
"""
Assemble the final app/src/main/assets/lessons.json — the full workbook.

Sources, in order of preference for any given lesson number:
  1. The parse of the saved email in emails/lessonNNN.html (the uniform source,
     and the only one that carries the italic emphasis).
  2. Any older vetted entry already in lessons.json — used only to fill a number
     that no email can supply.

We ship EVERY lesson we can parse cleanly (a non-empty phrase and body; the
video link is a bonus — the earliest lessons' emails simply don't carry one).
The list is sorted by lesson number, and the app cycles through it one lesson
per calendar day (see Lessons.java), so a complete list means the right lesson
shows every day of the year.

Any gaps in 1..365 are printed at the end so we know what's still worth
fetching. Rerun after fetching more emails to extend coverage.

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


def valid(lesson):
    return bool(lesson.get("phrase")) and bool(lesson.get("body"))


# 1. Vetted entries already in lessons.json.
base = {}
if os.path.exists(OUT):
    for l in json.load(open(OUT, encoding="utf-8")):
        if valid(l):
            base[l["number"]] = l

# 2. Everything else we can parse from the emails.
emailed = {}
for f in glob.glob(os.path.join(EMAILS, "lesson*.html")):
    d = b.extract(f)
    d.pop("_warn", None)
    if valid(d):
        emailed[d["number"]] = d

# Day 1 comes from the special "Welcome" email (Introduction + Lesson 1 together).
welcome = os.path.join(EMAILS, "welcome.html")
if os.path.exists(welcome):
    d = b.extract_welcome(welcome)
    d.pop("_warn", None)
    if valid(d):
        emailed[1] = d

merged = {}
for num in set(base) | set(emailed):
    merged[num] = emailed[num] if num in emailed else base[num]

out = [merged[num] for num in sorted(merged)]

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)

nums = sorted(merged)
with_video = sum(1 for l in out if l.get("video"))
print(f"Shipped {len(out)} lessons  (range {nums[0]}..{nums[-1]}, {with_video} with video links)")
missing = [n for n in range(1, 366) if n not in merged]
if missing:
    print(f"Still missing from 1..365 ({len(missing)}): {missing}")
else:
    print("Complete workbook: every lesson 1..365 present.")
