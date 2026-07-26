#!/usr/bin/env python3
"""
build_text_days.py — Build app/src/main/assets/text_days.json (the Daily Text).

The Workbook side of the app is built from the saved lesson emails by
finalize_lessons.py. This is the Text side: Marianne's per-day Text sessions,
sourced from the authoritative members.marianne.com scrape that lives outside
this repo in ~/Documents/ACIM/Text.

Two inputs, because the day data and the video hash live in different places:

  data/days_canonical.json   the reading itself — menu label, sections, and the
                             bare Vimeo id for each day
  .work/days_raw/day-NNN.html  the saved day page, which is the only place the
                             Vimeo *access hash* and the italics survive

The hash matters. These videos are unlisted, so vimeo.com/1040972397 alone is a
404 — it only opens as vimeo.com/1040972397/f69a55d4a3. That two-part form is
exactly what the Workbook lessons already use, so the app's existing inline
player plays these with no change.

The italics matter too. The canonical scrape kept the words but dropped their
markup, so T-18.I.6:9 arrived as "And above all, be not afraid of it" with the
emphasis the Course itself puts on that line simply gone — 907 such runs across
365 days. The saved page still has them as <i>, so they are lifted back out of
it here and reattached to the canonical sentence they belong to.

Usage:
  python3 tools/build_text_days.py                    # rebuild from the default source
  python3 tools/build_text_days.py --text-root ~/somewhere/else
"""
from __future__ import annotations

import argparse
import html as _html
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "app" / "src" / "main" / "assets" / "text_days.json"

# The saved day page embeds the player as
#   player.vimeo.com/video/1040972397?color&autopause=0&...&h=f69a55d4a3
# inside a JSON blob, so the URL arrives double-escaped and with \/ separators.
VIMEO_RE = re.compile(r"vimeo\.com/video/(\d+)[^\"'<> ]*?[?&]h=([a-zA-Z0-9]+)")

# Placeholders for an italic run's edges. They ride through escaping and the
# sentence-number pass untouched (esc only rewrites & < >), and become <i>/</i>
# at the very end — which saves having to track how the offsets shift as markup
# is inserted around them.
IT_OPEN, IT_CLOSE = "\x01", "\x02"

# The FIP edition numbers individual sentences within a paragraph, and the
# scraper leaves those digits inline ("... voluntary. 4Free will does not ...").
# Lift them to real superscripts, but only where the pattern is unambiguous:
# a digit wedged between the end of one sentence and the start of the next.
# The marker characters count as sentence edges: a sentence can end on an
# italic ("...be not afraid of it.") or begin with one ("11That they have").
SENTENCE_NUM_RE = re.compile(
    rf"(?<=[A-Za-z”\"'?!.{IT_OPEN}{IT_CLOSE}])\s*(\d{{1,2}})([{IT_OPEN}]?)([A-Z“\"'(])")

# One day's page as saved: sentences carry an id of the form "219#6:9",
# i.e. <source>#<paragraph>:<sentence>, and italics sit inside them.
SENTENCE_RE = re.compile(r'<span data-sentence-id="([^"]+)"[^>]*>(.*?)</span>', re.S)
ITALIC_RE = re.compile(r"<(i|em)\b[^>]*>(.*?)</\1>", re.S)
ANY_TAG_RE = re.compile(r"<[^>]+>")


def esc(s: str) -> str:
    """Escape for Html.fromHtml, which is what renders the body in the app."""
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def vimeo_url(day: int, raw_dir: Path, bare_id: str) -> str:
    """Recover the full unlisted URL for a day, or "" if we can't."""
    page = raw_dir / f"day-{day:03d}.html"
    if page.is_file():
        # Unescape twice: the URL sits HTML-escaped inside a JSON string that is
        # itself HTML-escaped into the page.
        text = _html.unescape(_html.unescape(page.read_text(encoding="utf-8", errors="replace")))
        m = VIMEO_RE.search(text.replace("\\/", "/"))
        if m:
            return f"https://vimeo.com/{m.group(1)}/{m.group(2)}"
    return ""


def italic_runs(page: Path) -> list[tuple[int, list[str]]]:
    """
    The italicised phrases on one saved day page, as plain text, in reading
    order: [(paragraph number, [phrase, ...]), ...].

    Keyed by position rather than by paragraph number alone, because a day
    spanning two sections can hold two different paragraph 2s. Pairing this
    list against the canonical paragraphs in order keeps them apart.
    """
    if not page.is_file():
        return []
    html_text = page.read_text(encoding="utf-8", errors="replace")
    paras: list[tuple[int, list[str]]] = []
    seen_pid = None
    for sid, inner in SENTENCE_RE.findall(html_text):
        pid = sid.split(":")[0]                       # "219#6:9" -> "219#6"
        try:
            number = int(pid.split("#")[1])
        except (IndexError, ValueError):
            continue
        if pid != seen_pid:
            paras.append((number, []))
            seen_pid = pid
        for m in ITALIC_RE.finditer(inner):
            run = _html.unescape(ANY_TAG_RE.sub("", m.group(2))).strip()
            if run:
                paras[-1][1].append(run)
    return paras


def mark_italics(text: str, runs: list[str]) -> str:
    """
    Put the markers back around each italicised phrase in a canonical sentence.

    Matched on whitespace-insensitive text and scanned left to right, so a
    phrase italicised twice in one paragraph lands on the right occurrence.
    Anything that doesn't match is dropped rather than guessed at.
    """
    out, pos = [], 0
    for run in runs:
        pattern = re.compile(r"\s+".join(re.escape(w) for w in run.split()))
        m = pattern.search(text, pos)
        if not m:
            continue
        start = m.start()
        # Stripping <i> left a space behind: "11That" was saved as "11 That",
        # which also hides the sentence number from the superscript pass. Drop
        # it, so the number reads as a number again.
        if start >= 2 and text[start - 1] == " " and text[start - 2].isdigit():
            out.append(text[pos:start - 1])
        else:
            out.append(text[pos:start])
        out.append(IT_OPEN + m.group(0) + IT_CLOSE)
        pos = m.end()
    out.append(text[pos:])
    return "".join(out)


def render_body(entry: dict, italics: list[tuple[int, list[str]]]) -> str:
    """
    Render one day's reading as the app's body format: paragraphs separated by
    blank lines, with <b>/<sup> markup that Html.fromHtml understands. Matches
    the convention finalize_lessons.py uses for Workbook lessons.
    """
    blocks: list[str] = []
    cursor = 0
    for sec in entry.get("sections", []):
        heading = (sec.get("heading") or "").strip()
        if heading:
            blocks.append(f"<b>{esc(heading)}</b>")
        for para in sec.get("paragraphs", []):
            text = (para.get("text") or "").strip()
            if not text:
                continue
            n = para.get("n")
            # Walk forward to this paragraph in the saved page. Skipping rather
            # than searching keeps the two in step when a paragraph number
            # repeats across sections.
            while cursor < len(italics) and italics[cursor][0] != n:
                cursor += 1
            if cursor < len(italics):
                text = mark_italics(text, italics[cursor][1])
                cursor += 1
            # The sentence numbers are reference marks, not content — keep them
            # available but faint, so they don't compete with the reading.
            body = SENTENCE_NUM_RE.sub(
                lambda m: f'<sup><font color="#BCB3A2">{m.group(1)}</font></sup>'
                          f'{m.group(2)}{m.group(3)}',
                esc(text))
            body = body.replace(IT_OPEN, "<i>").replace(IT_CLOSE, "</i>")
            blocks.append(f"<b>{n}.</b> {body}" if n else body)
    return "\n\n".join(blocks)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--text-root", default="~/Documents/ACIM/Text",
                    help="the ACIM Text pipeline folder (default: %(default)s)")
    args = ap.parse_args()

    root = Path(args.text_root).expanduser()
    canonical = root / "data" / "days_canonical.json"
    raw_dir = root / ".work" / "days_raw"

    if not canonical.is_file():
        print(f"error: no days_canonical.json at {canonical}", file=sys.stderr)
        return 1

    days = json.loads(canonical.read_text(encoding="utf-8"))["days"]

    out, no_video, no_body = [], [], []
    for key in sorted(days, key=int):
        n = int(key)
        entry = days[key]
        url = vimeo_url(n, raw_dir, entry.get("vimeo_id", ""))
        body = render_body(entry, italic_runs(raw_dir / f"day-{n:03d}.html"))
        if not url:
            no_video.append(n)
        if not body:
            no_body.append(n)
        out.append({
            "n": n,
            "label": (entry.get("menu_label") or f"Day {n}").strip(),
            "video": url,
            "body": body,
        })

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    italics = sum(d["body"].count("<i>") for d in out)
    print(f"wrote {OUT.relative_to(REPO)}: {len(out)} days, "
          f"{OUT.stat().st_size / 1e6:.1f} MB, {italics} italic runs restored")
    # Loud about gaps rather than silently shipping a day with a dead play button.
    if no_video:
        print(f"warning: {len(no_video)} day(s) with no recoverable video hash: "
              f"{no_video[:12]}{' ...' if len(no_video) > 12 else ''}", file=sys.stderr)
    if no_body:
        print(f"warning: {len(no_body)} day(s) with no reading text: {no_body}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
