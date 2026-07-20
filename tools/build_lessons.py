#!/usr/bin/env python3
"""
Read every lessons_source/lessonNNN.html digest and emit a single
app/src/main/assets/lessons.json that the app loads at runtime.

For each lesson we pull out:
  number   - e.g. 100 (from the <h1> "Lesson 100")
  title    - "Lesson 100"
  phrase   - the italic idea line under the title
  video    - the "Watch today's lesson video" URL
  body     - the full lesson text (the "The Lesson" section, plus the review
             preamble on review days) with the Marianne commentary EXCLUDED.

To add more lessons later: drop lessonNNN.html into lessons_source/ and rerun:
    python3 tools/build_lessons.py
"""
import glob
import html
import json
import os
import re

HERE = os.path.dirname(__file__)
SRC = os.path.abspath(os.path.join(HERE, "..", "lessons_source"))
OUT = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "lessons.json"))

EYEBROW_SPLIT = re.compile(r'<div class="mw-eyebrow"[^>]*>(.*?)</div>', re.S)
PARA = re.compile(r'<p class="mw-text"[^>]*>(.*?)</p>', re.S)
TAG = re.compile(r'<[^>]+>')
WS = re.compile(r'\s+')


def clean(fragment):
    """HTML fragment -> clean single-line text."""
    text = TAG.sub('', fragment)
    text = html.unescape(text)
    return WS.sub(' ', text).strip()


def extract(path):
    src = open(path, encoding='utf-8').read()

    title = clean(re.search(r'<h1 class="mw-h1"[^>]*>(.*?)</h1>', src, re.S).group(1))
    number = int(re.search(r'(\d+)', title).group(1))
    phrase = clean(re.search(r'<p class="mw-subtitle"[^>]*>(.*?)</p>', src, re.S).group(1))

    vid = re.search(r'class="mw-link"\s+href="([^"]+)"', src)
    video = vid.group(1) if vid else ""

    # Walk the document in order. Each mw-eyebrow label introduces a section;
    # keep paragraphs from "The Lesson" and review "Section Preamble" sections,
    # skip the "Commentary" section.
    paras = []
    parts = EYEBROW_SPLIT.split(src)   # [before, label1, chunk1, label2, chunk2, ...]
    for i in range(1, len(parts), 2):
        label = clean(parts[i])
        chunk = parts[i + 1] if i + 1 < len(parts) else ""
        if label.startswith("The Lesson") or label.startswith("Section Preamble"):
            for m in PARA.finditer(chunk):
                p = clean(m.group(1))
                if p:
                    paras.append(p)

    body = "\n\n".join(paras)
    return {"number": number, "title": title, "phrase": phrase, "video": video, "body": body}


def main():
    lessons = [extract(p) for p in glob.glob(os.path.join(SRC, "lesson*.html"))]
    lessons.sort(key=lambda l: l["number"])

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(lessons, f, ensure_ascii=False, indent=2)

    print(f"Wrote {len(lessons)} lessons to {OUT}")
    for l in lessons:
        print(f'  {l["number"]}: "{l["phrase"]}"  ({len(l["body"])} chars, video={"yes" if l["video"] else "NO"})')


if __name__ == "__main__":
    main()
