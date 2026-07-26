#!/usr/bin/env python3
"""
emails_from_eml.py — Recover lesson emails (and their italics) from the raw
.eml archive.

The italics matter: Marianne's emails italicise the quoted Course passages and
the verses you're meant to hold during practice, and the app renders that
emphasis. Lessons whose email was missing lost it entirely.

The complication is that the raw emails use a different template from the ones
already in emails/. Those wrap each paragraph in <p>; the raw ones lay text out
in <div>s separated by double <br>, with only a handful of <p> tags in the
footer. build_lessons_from_email.py extracts <p> tags, so a raw email dropped
straight in parses to almost nothing and the lesson silently falls back to a
plain-text source — which is how a naive import made things *worse*, taking
italics from 190 lessons down to 41.

So this converts the raw layout into the <p>-per-paragraph shape the parser
already understands: double <br> (or a block boundary) ends a paragraph, a
single <br> is a line break within one, and the inline tags that carry meaning
(<em>, <strong>, links) are preserved.

Nothing is overwritten blindly. Each converted email is only adopted if it
actually improves that lesson — see --apply.

Usage:
  python3 tools/emails_from_eml.py                   # report, write nothing
  python3 tools/emails_from_eml.py --apply           # write improved emails only
"""
from __future__ import annotations

import argparse
import email
import email.policy
import html as _html
import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RAW = Path("~/Documents/ACIM/Workbook/.work/raw_emails").expanduser()
EMAILS = REPO / "emails"

# Tags worth keeping: emphasis carries meaning, and the anchor holds the Vimeo
# link the parser recovers separately.
KEEP = re.compile(r"(?is)^</?(?:em|strong|i|b|a)\b")

# Stands in for a <br> while block structure is worked out, so a line break the
# email actually asked for can't be confused with one that fell out of a <div>.
BR = "\x0b"

# Stamped on our own output so a later run can tell a converted email from a
# hand-saved one and refresh it, rather than having to out-score itself.
MARKER = "<!-- converted by tools/emails_from_eml.py -->"


def is_ours(html: str) -> bool:
    """True if this file was written by this script (the early runs predate the
    marker, and are recognisable by the exact shell they emit)."""
    return MARKER in html[:200] or html.startswith("<html><body>\n<p>")


# The masthead and download blurbs around the lesson. These matter because one
# of them reads "To play Lesson 104 directly from your computer" — and the
# parser takes the FIRST paragraph naming the lesson as the header, then treats
# whatever follows as the day's idea. Left in, every converted lesson's idea
# became "To download the lesson to your computer or itunes".
BOILERPLATE = re.compile(
    r"having trouble viewing|directly from your computer|download the lesson"
    r"|download lesson|click here|unsubscribe|sent with love", re.I)

ANCHOR = re.compile(r"""(?is)<a\b[^>]*href=["']([^"']+)["'][^>]*>.*?</a>""")


def neutralise_boilerplate(block: str) -> str:
    """
    Strip the wording from a boilerplate block but keep its links.

    The links can't simply be dropped: the Vimeo URL for the day is recovered
    from exactly these anchors. So the href survives and only the prose — which
    is what confuses the header matcher — is thrown away.
    """
    if not BOILERPLATE.search(re.sub(r"(?s)<[^>]+>", " ", block)):
        return block
    hrefs = ANCHOR.findall(block)
    # Empty link text: the href still reaches find_video, but nothing visible
    # survives into the lesson body. With placeholder text the word leaked into
    # the reading itself.
    return " ".join(f'<a href="{h}"></a>' for h in hrefs)


def to_paragraphs(html: str) -> str:
    """Rewrite a <div>/<br> email as one <p> per paragraph."""
    h = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", " ", html)

    # A <br> is kept as a <br>, rather than being flattened to a space.
    #
    # It was flattened before, and that cost the verses their shape: Lesson 104's
    # "I seek but what belongs to me in truth, / And joy and peace are my
    # inheritance." arrived as one run-on line. Promoting every <br> to a
    # paragraph break instead goes too far the other way — the idea line wraps
    # across a <br> in some emails, and Lesson 63 lost "through my forgiveness"
    # off the end of its own title. So the break is neither dropped nor promoted:
    # it survives as a line break inside the paragraph, and the verse can be read
    # off it downstream.
    #
    # A sentinel rather than the tag itself, because block boundaries below also
    # emit newlines and only the real <br>s should become line breaks.
    h = re.sub(r"(?i)<br[^>]*>", BR, h)
    h = re.sub(r"(?i)</(div|p|tr|td|table|tbody|h[1-6]|li|ul|ol)>", "\n\n", h)
    h = re.sub(r"(?i)<(div|p|tr|td|table|tbody|h[1-6]|li|ul|ol)[^>]*>", "\n", h)

    # Drop every other tag, keeping the inline ones that matter.
    def strip(m: re.Match) -> str:
        return m.group(0) if KEEP.match(m.group(0)) else " "

    h = re.sub(r"(?s)<[^>]+>", strip, h)

    # A doubled <br> is a blank line, which is how these emails space paragraphs.
    h = re.sub(rf"{BR}\s*{BR}[{BR}\s]*", "\n\n", h)

    out = []
    for block in re.split(r"\n\s*\n+", h):
        block = neutralise_boilerplate(block)
        # A newline here came from a block boundary, not a <br> — a wrapped
        # source line, nothing the reader should see.
        text = re.sub(r"[ \t]*\n[ \t]*", " ", block)
        text = re.sub(rf"[ \t]*{BR}[ \t]*", "<br>", text)
        text = re.sub(r"[ \t]{2,}", " ", text).strip()
        text = re.sub(r"^(?:<br>)+|(?:<br>)+$", "", text).strip()
        if not text:
            continue
        # Blocks that are nothing but markup or whitespace add noise — unless
        # they carry a link, which is where the day's video URL lives.
        if not _html.unescape(re.sub(r"(?s)<[^>]+>", "", text)).strip():
            if "<a " not in text:
                continue
        out.append(f"<p>{text}</p>")
    return MARKER + "\n<html><body>\n" + "\n".join(out) + "\n</body></html>"


def convert_all() -> dict[int, str]:
    """lesson number -> converted HTML, for every raw email we can read."""
    out: dict[int, str] = {}
    for f in sorted(RAW.glob("*.eml")):
        m = re.search(r"(\d+)", f.name)
        if not m:
            continue
        n = int(m.group(1))
        if not 1 <= n <= 365:
            continue
        msg = email.message_from_bytes(f.read_bytes(), policy=email.policy.default)
        part = msg.get_body(preferencelist=("html",))
        if part is None:
            continue
        out[n] = to_paragraphs(part.get_content())
    return out


def italics_in(html: str) -> int:
    return len(re.findall(r"(?i)<(?:i|em)\b", html))


def _parser():
    """The real email parser, so we can score a candidate exactly as it will
    be read when the lessons are rebuilt."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "ble", REPO / "tools" / "build_lessons_from_email.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


PARSER = None
_TMPDIR = REPO / "tools" / ".score_tmp"


def score(html: str, n: int) -> tuple[int, int]:
    """
    (italic runs, body length) once parsed. Ordered so emphasis wins first and
    length breaks ties — a version that recovers italics is worth having even
    if it's slightly shorter, but an empty parse can never win.
    """
    global PARSER
    if PARSER is None:
        PARSER = _parser()
    # The parser reads the lesson number off the filename, so the scratch file
    # has to be named like a real one or every candidate scores as a failure.
    _TMPDIR.mkdir(exist_ok=True)
    tmp = _TMPDIR / f"lesson{n:03d}.html"
    tmp.write_text(html, encoding="utf-8")
    try:
        r = PARSER.extract(str(tmp)) or {}
    except Exception:
        return (-1, -1)
    if r.get("_warn"):
        return (-1, -1)
    body = r.get("body") or ""
    if not body.strip() or not (r.get("phrase") or "").strip():
        return (-1, -1)
    return (len(re.findall(r"<i>", body)), len(body))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true",
                    help="write converted emails where they improve on what's there")
    args = ap.parse_args()

    converted = convert_all()
    print(f"raw emails converted: {len(converted)}")

    added, improved, kept = [], [], []
    for n, html in sorted(converted.items()):
        dest = EMAILS / f"lesson{n:03d}.html"
        if not dest.is_file():
            added.append(n)
            if args.apply:
                dest.write_text(html, encoding="utf-8")
            continue
        # Judge on what each version actually PARSES to, not on how its markup
        # looks. Guessing from the raw HTML was wrong twice over: counting <p>
        # tags missed lesson 111 (seven footer paragraphs looked converted while
        # its body parsed down to download links), and preferring the raw email
        # wherever divs outnumbered paragraphs *lost* italics on 24 lessons whose
        # older template mixes divs with perfectly good <p> paragraphs.
        existing = dest.read_text(encoding="utf-8", errors="replace")
        # A file we wrote ourselves is a cache of this conversion, not a rival
        # source: refresh it so improvements to the converter actually land.
        # Scoring it would compare the converter against its own older output,
        # and a tie loses.
        if is_ours(existing):
            improved.append(n)
            if args.apply:
                dest.write_text(html, encoding="utf-8")
            continue
        if score(html, n) > score(existing, n):
            improved.append(n)
            if args.apply:
                dest.write_text(html, encoding="utf-8")
        else:
            kept.append(n)

    print(f"  missing emails this supplies: {len(added)} {added}")
    print(f"  existing emails with no italics that the raw one has: {len(improved)}")
    print(f"    {improved[:40]}{' ...' if len(improved) > 40 else ''}")
    print(f"  left alone: {len(kept)}")
    if not args.apply:
        print("\n(--check only; nothing written. Re-run with --apply.)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
