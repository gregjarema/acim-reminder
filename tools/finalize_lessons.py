#!/usr/bin/env python3
"""
Assemble the final app/src/main/assets/lessons.json — the full workbook.

Three sources feed each lesson, merged FIELD BY FIELD (not whole-record) so a
gap in one source doesn't blank out good data another source already has:

  1. emails/lessonNNN.html  - parsed by build_lessons_from_email.py. Covers all
     365 lessons and is the only source with italic emphasis preserved, but a
     handful of individual emails are missing their video link (that
     particular message just didn't include one — see Lesson 99).
  2. lessons_source/lessonNNN.html - the original clean digests for lessons
     98-114 that Greg uploaded directly; every one has a verified video link,
     so this fills in any video an email happened to be missing.
  3. The lessons.json already on disk - last-resort fallback for anything
     only ever captured there.

For each field (phrase/body/video) we take the first non-empty value in that
priority order. Concretely: emailed body wins (keeps italics), but if an
email's video is blank we fall back to the digest's or the existing entry's.

Any gaps in 1..365 are printed at the end. Rerun after fetching more emails
to extend coverage.

Usage: python3 tools/finalize_lessons.py
"""
import glob
import importlib.util
import json
import os

HERE = os.path.dirname(__file__)
OUT = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "lessons.json"))
EMAILS = os.path.abspath(os.path.join(HERE, "..", "emails"))
DIGESTS = os.path.abspath(os.path.join(HERE, "..", "lessons_source"))


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, filename))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


b = load_module("b", "build_lessons_from_email.py")
bd = load_module("bd", "build_lessons.py")
med = load_module("med", "meditation.py")


def valid(lesson):
    return bool(lesson.get("phrase")) and bool(lesson.get("body"))


# 1. Parsed from the saved emails (broadest coverage, keeps italics).
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

# 2. The original clean digests (98-114) - authoritative for their video links.
digest = {}
if os.path.isdir(DIGESTS):
    for f in glob.glob(os.path.join(DIGESTS, "lesson*.html")):
        d = bd.extract(f)
        if valid(d):
            digest[d["number"]] = d

# 3. Whatever's already shipped, as a last-resort fallback.
base = {}
if os.path.exists(OUT):
    for l in json.load(open(OUT, encoding="utf-8")):
        if valid(l):
            base[l["number"]] = l

SOURCES = (emailed, digest, base)


def first_nonempty(num, field):
    for src in SOURCES:
        if num in src and src[num].get(field):
            return src[num][field]
    return ""


merged = {}
for num in set(emailed) | set(digest) | set(base):
    phrase = first_nonempty(num, "phrase")
    body = first_nonempty(num, "body")
    merged[num] = {
        "number": num,
        "title": first_nonempty(num, "title") or f"Lesson {num}",
        "phrase": phrase,
        "meditation": med.extract_meditation(body, phrase),
        "video": first_nonempty(num, "video"),
        "body": body,
    }

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
