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
                             Vimeo *access hash* survives (`...?h=f69a55d4a3`)

The hash matters. These videos are unlisted, so vimeo.com/1040972397 alone is a
404 — it only opens as vimeo.com/1040972397/f69a55d4a3. That two-part form is
exactly what the Workbook lessons already use, so the app's existing inline
player plays these with no change.

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

# The FIP edition numbers individual sentences within a paragraph, and the
# scraper leaves those digits inline ("... voluntary. 4Free will does not ...").
# Lift them to real superscripts, but only where the pattern is unambiguous:
# a digit wedged between the end of one sentence and the start of the next.
SENTENCE_NUM_RE = re.compile(r"(?<=[A-Za-z”\"'?!.])\s*(\d{1,2})([A-Z“\"'(])")


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


def render_body(entry: dict) -> str:
    """
    Render one day's reading as the app's body format: paragraphs separated by
    blank lines, with <b>/<sup> markup that Html.fromHtml understands. Matches
    the convention finalize_lessons.py uses for Workbook lessons.
    """
    blocks: list[str] = []
    for sec in entry.get("sections", []):
        heading = (sec.get("heading") or "").strip()
        if heading:
            blocks.append(f"<b>{esc(heading)}</b>")
        for para in sec.get("paragraphs", []):
            text = (para.get("text") or "").strip()
            if not text:
                continue
            # The sentence numbers are reference marks, not content — keep them
            # available but faint, so they don't compete with the reading.
            body = SENTENCE_NUM_RE.sub(
                lambda m: f'<sup><font color="#BCB3A2">{m.group(1)}</font></sup>{m.group(2)}',
                esc(text))
            n = para.get("n")
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
        body = render_body(entry)
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

    print(f"wrote {OUT.relative_to(REPO)}: {len(out)} days, "
          f"{OUT.stat().st_size / 1e6:.1f} MB")
    # Loud about gaps rather than silently shipping a day with a dead play button.
    if no_video:
        print(f"warning: {len(no_video)} day(s) with no recoverable video hash: "
              f"{no_video[:12]}{' ...' if len(no_video) > 12 else ''}", file=sys.stderr)
    if no_body:
        print(f"warning: {len(no_body)} day(s) with no reading text: {no_body}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
