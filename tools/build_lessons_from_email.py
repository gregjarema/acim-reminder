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

# Two email templates over the years: a newer one that wraps each paragraph in
# <p class="bard-text-block">, and an older one that uses inline <b>/<i>/<span>
# for that class but still puts real paragraphs in plain <p> tags. Extracting
# ALL <p> tags (any class) handles both; we then locate the lesson header and
# trim the footer boilerplate ourselves.
PARA = re.compile(r'<p\b[^>]*>(.*?)</p>', re.S)
ANCHOR = re.compile(r'<a\s+href="([^"]+)"[^>]*>(.*?)</a>', re.S)
TAG = re.compile(r'<[^>]+>')
WS = re.compile(r'\s+')

# Italic emphasis carries meaning in these lessons (quoted Course passages, the
# idea line, prayers), so we keep <i>/<em> in the body and render it in the app.
I_OPEN = re.compile(r'<(?:i|em)\b[^>]*>', re.I)
I_CLOSE = re.compile(r'</(?:i|em)\s*>', re.I)
EMPTY_I = re.compile(r'<i>\s*</i>')

# Paragraphs at/after any of these mark the email footer — never part of a lesson.
FOOTER = re.compile(
    r'^\s*(unsubscribe|marianne williamson\b|p\.?\s*o\.?\s*box|'
    r'this email was sent|view this email|to ensure delivery|'
    r'©|copyright|manage your|update your preferences|'
    r'from "a course in miracles"|from .a course in miracles.)',
    re.I)

# The masthead boilerplate that precedes the lesson in every email.
BOILER = re.compile(
    r'^\s*(your login credentials|today is day\b|every morning\b|'
    r'to view (the current|a previous)|access the video|watch today|'
    r'to view the current lesson)',
    re.I)

# Section-title lines ("Review VI", "Final Lessons", "What Is Forgiveness?") that
# head a multi-day period rather than name a single day's idea.
SECTION = re.compile(r'^\s*(review\b|final lessons\b|introduction\b|what is\b|part\b)', re.I)


def clean(fragment):
    text = TAG.sub(' ', fragment)
    text = html.unescape(text)
    return WS.sub(' ', text).strip()


def clean_html(fragment):
    """Like clean(), but keep italic emphasis as <i>...</i> for the app to render."""
    s = I_OPEN.sub('\x01', fragment)
    s = I_CLOSE.sub('\x02', s)
    s = TAG.sub(' ', s)              # drop every other tag
    s = html.unescape(s)            # entities -> real characters
    # Re-escape so the stored string is valid HTML, then restore the italics.
    s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    s = s.replace('\x01', '<i>').replace('\x02', '</i>')
    s = EMPTY_I.sub('', s)
    return WS.sub(' ', s).strip()


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

    # Two parallel views of every paragraph, kept index-aligned: the plain text
    # (used to find the header, phrase, footer) and the italics-preserving HTML
    # (used to build the body so emphasis survives).
    raw = PARA.findall(src)
    pairs = [(clean(p), clean_html(p)) for p in raw]
    pairs = [(t, h) for t, h in pairs if t]
    paras = [t for t, _ in pairs]
    rich = [h for _, h in pairs]

    def result(phrase, body_richs):
        body = "\n\n".join(b for b in body_richs if b)
        # Some later "prayer" emails are just the prayer in a single paragraph with
        # no separate idea line, which would leave the body empty. Peel the first
        # sentence off as the phrase and keep the remainder as the body.
        if not body and len(phrase) > 120:
            m = re.match(r'(.+?[.!?])\s+(.+)', phrase, re.S)
            if m:
                phrase, body = m.group(1), m.group(2)
        return {"number": number, "title": "Lesson " + str(number),
                "phrase": phrase, "video": find_video(src), "body": body}

    # Drop everything from the footer boilerplate onward so it never leaks into
    # a lesson body (the all-<p> sweep would otherwise pick up the address block).
    for i, p in enumerate(paras):
        if FOOTER.search(p):
            paras = paras[:i]
            rich = rich[:i]
            break

    # Find the header line naming this lesson. The header varies between emails
    # ("Lesson 98", "A Course In Miracles Lesson 37"), so match the number with a
    # word boundary on both sides — the trailing (?!\d) stops "Lesson 2" matching
    # "Lesson 20". The idea line follows the header, then the body.
    label_re = re.compile(r'\bLesson\s+0*' + str(number) + r'(?!\d)', re.I)
    start = None
    for i, p in enumerate(paras):
        if label_re.search(p):
            start = i
            break

    if start is not None and start + 1 < len(paras):
        return result(paras[start + 1], rich[start + 2:])

    # No "Lesson N" header. Two known header-less shapes:
    #   (a) a mis-numbered "Lesson X" line still heads the email — use it.
    if paras and re.match(r'(?i)^(a course in miracles\s+)?lesson\s+\d+\b', paras[0]) \
            and len(paras) > 1:
        return result(paras[1], rich[2:])

    #   (b) the "reflection/prayer" template: no header at all — the idea (or the
    #       day's prayer) is simply the first real paragraph after any masthead.
    idx = 0
    while idx < len(paras) and BOILER.search(paras[idx]):
        idx += 1
    if idx < len(paras) and not SECTION.search(paras[idx]) and idx + 1 < len(paras):
        return result(paras[idx], rich[idx + 1:])

    #   (c) a review / final-lessons intro email: it heads a multi-day period, so
    #       there's no per-day header. Take the first short "idea" line (e.g. "I am
    #       not a body. I am free.") as the phrase and keep the intro as the body.
    if paras and SECTION.search(paras[0]):
        for i, p in enumerate(paras):
            if i == 0 or SECTION.search(p) or p.lower() == "introduction":
                continue
            if len(p) <= 60 and re.search(r'[.!?]$', p):
                body_richs = [rich[j] for j, q in enumerate(paras)
                              if not SECTION.search(q) and q.lower() != "introduction"]
                return result(p, body_richs)

    return {"number": number, "title": "Lesson " + str(number),
            "phrase": "", "video": find_video(src), "body": "", "_warn": "no lesson label"}


def extract_welcome(path):
    """Day 1's email ("Welcome to Mornings with Marianne!") carries both the
    Workbook Introduction and Lesson 1. We show them together: the phrase is
    Lesson 1's idea, and the body is the Introduction followed by Lesson 1."""
    src = open(path, encoding='utf-8').read()
    pairs = [(clean(p), clean_html(p)) for p in PARA.findall(src)]
    pairs = [(t, h) for t, h in pairs if t]
    plain = [t for t, _ in pairs]
    rich = [h for _, h in pairs]

    # Trim the footer.
    for i, p in enumerate(plain):
        if FOOTER.search(p):
            plain, rich = plain[:i], rich[:i]
            break

    intro_i = next((i for i, p in enumerate(plain) if p.strip().lower() == "introduction"), None)
    lesson_i = next((i for i, p in enumerate(plain)
                     if re.search(r'\blesson\s+1(?!\d)', p, re.I)), None)
    if intro_i is None or lesson_i is None or lesson_i + 1 >= len(plain):
        return {"number": 1, "title": "Lesson 1", "phrase": "",
                "video": find_video(src), "body": "", "_warn": "welcome parse"}

    phrase = plain[lesson_i + 1]
    intro_body = rich[intro_i + 1:lesson_i]
    lesson_body = rich[lesson_i + 2:]
    parts = (["<b>Introduction</b>"] + intro_body
             + ["<b>Lesson 1</b>"] + lesson_body)
    return {"number": 1, "title": "Lesson 1", "phrase": phrase,
            "video": find_video(src), "body": "\n\n".join(b for b in parts if b)}


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
