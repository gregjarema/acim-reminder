#!/usr/bin/env python3
"""
Audit app/src/main/assets/lessons.json against lessons.acim.org.

lessons.acim.org is the Foundation for Inner Peace's own web edition, so its
wording, paragraph breaks and paragraph/sentence numbers are the reference. The
saved Marianne emails are the source of our text (and the only source of the
daily videos), but they hard-wrap sentences and have dropped text outright
(Lesson 201 arrived with only the Review VI introduction). This script finds
every such gap.

For each Workbook lesson 1..365 it fetches https://lessons.acim.org/en/chapters/
lesson-N, reads the sentences (numbered with <span class="snr">, or whole unnumbered paragraphs) and checks that each
one appears, wording-for-wording, in that lesson's `body` in lessons.json.
Formatting is ignored (tags, whitespace, curly vs straight quotes, dashes), so
this finds MISSING or ALTERED text, not layout differences.

The site is a courtesy, not a bulk mirror: pages are fetched one at a time with a
pause between requests, and cached on disk so a rerun costs nothing. robots.txt
allows /en/chapters/.

Usage:
  python3 tools/audit_against_acim_org.py                 # all 365
  python3 tools/audit_against_acim_org.py 190 205         # a range
  python3 tools/audit_against_acim_org.py --cache DIR     # where pages are cached
  python3 tools/audit_against_acim_org.py --json out.json # also dump parsed lessons
                                                          # (paragraph + sentence numbers)
"""
import argparse
import html
import json
import os
import re
import sys
import time
import urllib.request

HERE = os.path.dirname(__file__)
LESSONS = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "lessons.json"))
URL = "https://lessons.acim.org/en/chapters/lesson-{n}"
UA = "acim-reminder-audit/1.0 (personal app; one request at a time)"

TAG = re.compile(r"<[^>]+>")
PARA = re.compile(r'<p class="([^"]*)">(.*?)</p>', re.S)
SNR = re.compile(r'<span class="snr[^"]*">\s*(\d+)\s*</span>')
PNR = re.compile(r'<span class="pnr">\s*(\d+)\.\s*</span>')
BODY = re.compile(r'<div class="acim-text[^"]*">(.*?)</div>\s*</section>', re.S)


def fetch(n, cache_dir, delay):
    path = os.path.join(cache_dir, f"lesson-{n}.html")
    if os.path.exists(path):
        return open(path, encoding="utf-8").read()
    req = urllib.request.Request(URL.format(n=n), headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        text = r.read().decode("utf-8")
    os.makedirs(cache_dir, exist_ok=True)
    open(path, "w", encoding="utf-8").write(text)
    time.sleep(delay)
    return text


def parse_site(page):
    """-> list of paragraphs: {"p": paragraph number or None, "sentences": [(n, text)]}.

    A sentence runs from one <span class="snr"> to the next, or to the end of its
    <p>. <br/> inside a paragraph is a line break within a sentence group, so it
    becomes a space. The paragraph number is the <span class="pnr"> some
    paragraphs carry; the rest continue the previous one.
    """
    m = BODY.search(page)
    if not m:
        return []
    out = []
    for cls, inner in PARA.findall(m.group(1)):
        pm = PNR.search(inner)
        pnum = int(pm.group(1)) if pm else None
        inner = PNR.sub("", inner)
        pieces = SNR.split(inner)  # [lead, n1, text1, n2, text2, ...]
        sentences = []
        lead = _text(pieces[0])
        if lead and len(pieces) == 1:  # no sentence numbers (Review IV lists, headings)
            sentences.append((None, lead))
        for i in range(1, len(pieces) - 1, 2):
            sentences.append((int(pieces[i]), _text(pieces[i + 1])))
        out.append({"class": cls, "p": pnum, "sentences": sentences})
    return out


def _text(fragment):
    fragment = re.sub(r"<br\s*/?>", " ", fragment)
    fragment = html.unescape(TAG.sub("", fragment)).replace("\u00ad", "")  # soft hyphens
    return re.sub(r"\s+", " ", fragment).strip()


def norm(s):
    """Wording only: lowercase, curly->straight, dashes/ellipses/spaces folded, no tags."""
    s = html.unescape(TAG.sub("", s))
    s = (s.replace("’", "'").replace("‘", "'")
          .replace("“", '"').replace("”", '"')
          .replace("—", " ").replace("–", " ").replace("-", " ")
          .replace("…", " ").replace(" ", " "))
    s = re.sub(r"\(\d+\)", " ", s)          # the "(181)" lesson reference
    s = re.sub(r"[^a-z0-9' ]+", " ", s.lower())
    return re.sub(r"\s+", " ", s).strip()


def audit(lesson, paragraphs):
    # The idea line (`phrase`) is shown as the heading, so it counts as app text.
    body = norm(lesson["phrase"] + " " + lesson["body"])
    missing, unnumbered = [], []
    total = 0
    for para in paragraphs:
        for n, text in para["sentences"]:
            total += 1
            t = norm(text)
            if t and t not in body:
                # n is None for paragraphs the site leaves unnumbered: stock
                # lead-ins such as "The review for today covers the following
                # ideas:". Those are layout, not lost text, so list them apart.
                (unnumbered if n is None else missing).append((n, text))
    return total, missing, unnumbered


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("first", nargs="?", type=int, default=1)
    ap.add_argument("last", nargs="?", type=int, default=365)
    ap.add_argument("--cache", default=os.path.join(HERE, "..", ".acim_org_cache"))
    ap.add_argument("--delay", type=float, default=0.3)
    ap.add_argument("--json", help="write parsed site lessons (with numbering) here")
    args = ap.parse_args()

    lessons = {l["number"]: l for l in json.load(open(LESSONS, encoding="utf-8"))}
    parsed, problems, fetch_errors, lead_ins = {}, 0, [], []
    for n in range(args.first, args.last + 1):
        try:
            paras = parse_site(fetch(n, args.cache, args.delay))
        except Exception as e:  # network or HTTP error: keep going, report at the end
            fetch_errors.append((n, str(e)))
            continue
        parsed[n] = paras
        if n not in lessons:
            print(f"Lesson {n}: not in lessons.json")
            problems += 1
            continue
        total, missing, unnumbered = audit(lessons[n], paras)
        if unnumbered:
            lead_ins.append((n, [t for _, t in unnumbered]))
        if total == 0:
            print(f"Lesson {n}: no text found on the site page (parse problem?)")
            problems += 1
        elif missing:
            problems += 1
            print(f"Lesson {n}: {len(missing)} of {total} site sentences not in the app text")
            for sn, text in missing[:4]:
                print(f"    [{sn}] {text[:110]}")
            if len(missing) > 4:
                print(f"    ... and {len(missing) - 4} more")

    if lead_ins:
        stock = sorted({t for _, ts in lead_ins for t in ts})
        print(f"\nUnnumbered site lines not in the app text (informational), "
              f"{len(lead_ins)} lessons: " + "; ".join(f'"{t}"' for t in stock))
    checked = len(parsed)
    print(f"\nChecked {checked} lessons: {checked - problems} match, {problems} differ.")
    if fetch_errors:
        print(f"{len(fetch_errors)} fetch errors: {fetch_errors[:5]}")
    if args.json:
        json.dump(parsed, open(args.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"Wrote parsed site text to {args.json}")
    return 1 if problems or fetch_errors else 0


if __name__ == "__main__":
    sys.exit(main())
