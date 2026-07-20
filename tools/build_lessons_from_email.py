#!/usr/bin/env python3
"""
Parse the saved "Mornings with Marianne" lesson emails in emails/lessonNNN.html
and emit app/src/main/assets/lessons.json.

Each email contains the lesson number, the italic idea line, the full lesson
body, and a "Watch Today's ACIM ... Video" button. That button is a Keap
tracking link whose payload is zlib+base64 and decodes to JSON containing the
real Vimeo URL — so we recover clean video links with no network calls.

Usage:  python3 tools/build_lessons_from_email.py
"""
import base64
import glob
import html
import json
import os
import re
import zlib

HERE = os.path.dirname(__file__)
SRC = os.path.abspath(os.path.join(HERE, "..", "emails"))
OUT = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "lessons.json"))

PARA = re.compile(r'<p[^>]*class="[^"]*bard-text-block[^"]*"[^>]*>(.*?)</p>', re.S)
ANCHOR = re.compile(r'<a\s+href="([^"]+)"[^>]*>(.*?)</a>', re.S)
TAG = re.compile(r'<[^>]+>')
WS = re.compile(r'\s+')


def clean(fragment):
    text = TAG.sub(' ', fragment)
    text = html.unescape(text)
    return WS.sub(' ', text).strip()


def decode_keap(url):
    """Recover the real destination URL from a Keap tracking link, else None."""
    m = re.search(r'/v2/click/[^/]+/([A-Za-z0-9_\-]+)', url)
    if not m:
        return None
    s = m.group(1).replace('-', '+').replace('_', '/')
    s += '=' * (-len(s) % 4)
    try:
        data = json.loads(zlib.decompress(base64.b64decode(s)))
        return data.get("redirectUrl")
    except Exception:
        return None


def find_video(src):
    for href, text in ANCHOR.findall(src):
        if "watch today" in clean(text).lower():
            if "vimeo.com" in href:
                return href
            dest = decode_keap(href)
            if dest:
                return dest
            return href
    return ""


def extract(path):
    number = int(re.search(r'(\d+)', os.path.basename(path)).group(1))
    src = open(path, encoding='utf-8').read()

    paras = [clean(p) for p in PARA.findall(src)]
    paras = [p for p in paras if p]

    # Find the header line that ends with this lesson's number. The header text
    # varies between emails ("Lesson 98" vs "A Course In Miracles Lesson 2"), so
    # we match the number at the end of the line. The idea line follows it, then
    # the body. \b before the number keeps "Lesson 2" from matching "Lesson 20".
    label_re = re.compile(r'\bLesson\s+0*' + str(number) + r'\s*$', re.I)
    start = None
    for i, p in enumerate(paras):
        if label_re.search(p):
            start = i
            break

    if start is None or start + 1 >= len(paras):
        return {"number": number, "title": "Lesson " + str(number),
                "phrase": "", "video": find_video(src), "body": "", "_warn": "no lesson label"}

    phrase = paras[start + 1]
    body = "\n\n".join(paras[start + 2:])
    return {"number": number, "title": "Lesson " + str(number),
            "phrase": phrase, "video": find_video(src), "body": body}


def main():
    files = sorted(glob.glob(os.path.join(SRC, "lesson*.html")))
    lessons = [extract(p) for p in files]
    lessons.sort(key=lambda l: l["number"])

    warnings = []
    for l in lessons:
        if l.pop("_warn", None) or not l["body"] or not l["phrase"] or not l["video"]:
            warnings.append(l["number"])

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(lessons, f, ensure_ascii=False, indent=2)

    print(f"Wrote {len(lessons)} lessons to {OUT}")
    nums = [l["number"] for l in lessons]
    if nums:
        print(f"Range: {min(nums)}..{max(nums)}")
        missing = [n for n in range(min(nums), max(nums) + 1) if n not in set(nums)]
        if missing:
            print(f"MISSING numbers in range: {missing}")
    if warnings:
        print(f"Lessons with empty phrase/body/video (check these): {warnings}")


if __name__ == "__main__":
    main()
