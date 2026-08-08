#!/usr/bin/env python3
"""
recover_videos.py — Fill in missing / broken lesson video URLs in
app/src/main/assets/lessons.json by recovering the real Vimeo links from the
raw "Mornings with Marianne" email archive.

Why this exists
---------------
Every daily email carries a "Watch Today's ACIM ... Video" button. In the
repo those buttons survive as Keap/Infusionsoft tracking links, and many are
either corrupted (the base64 payload lost characters somewhere in the archive)
or point only at the password-gated showcase. The corrupted ones can't be
salvaged, and the tracking links themselves now 404 (they were per-recipient).
But the raw .eml originals still hold intact links, and each one ultimately
redirects to the plain vimeo.com/<id>/<hash> page the app can play forever.

This script, run where there IS network access (your machine), does two things
per lesson:

  1. Keap /v2/click links  -> decode the zlib+base64 payload OFFLINE to the
     redirectUrl. No network, no 404 risk. If that URL is a real video, use it.
  2. Anything else (infusion-links, infusionsoft linkClick, appspot, or a Keap
     link that only resolves to the showcase) -> follow the HTTP redirect chain
     to whatever vimeo.com URL it lands on.

Recovered links are normalised to `https://vimeo.com/<id>/<hash>` (the exact
shape the other lessons already use) and written back into lessons.json. Only
lessons that are currently empty or still on a /v2/click link are touched;
lessons that already have a direct Vimeo URL are left alone.

Usage
-----
  # dry run — report what it WOULD fill, write nothing
  python3 tools/recover_videos.py

  # actually patch lessons.json
  python3 tools/recover_videos.py --apply

  # point at a different archive, or fill from a manual CSV instead
  python3 tools/recover_videos.py --raw ~/path/to/raw_emails --apply
  python3 tools/recover_videos.py --links links.csv --apply

The optional CSV (--links) is a simple fallback for links you paste by hand:
one `lesson_number,url` per line, where url is a Keap link, a tracking link,
or a direct vimeo URL. Handy for the handful the archive can't cover.

Network is only used for step 2. Step 1 and direct-Vimeo CSV rows need none.
"""
from __future__ import annotations

import argparse
import base64
import csv
import email
import email.policy
import json
import re
import sys
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LESSONS = REPO / "app" / "src" / "main" / "assets" / "lessons.json"
DEFAULT_RAW = Path("~/Documents/ACIM/Workbook/.work/raw_emails").expanduser()

# Anchor whose visible text names the day's video button. The raw emails keep
# the real wording ("Watch Today's ACIM Video"); we match loosely so template
# drift ("watch the video", "watch today's lesson") still lands.
WATCH = re.compile(r"watch\b.*\bvideo|watch\s+today", re.I)
ANCHOR = re.compile(r"""(?is)<a\b[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""")
TAG = re.compile(r"(?s)<[^>]+>")

# vimeo.com/<id>/<hash> or player.vimeo.com/video/<id>?h=<hash> — capture both
# the numeric id and the unlisted-access hash wherever they appear.
VIMEO = re.compile(r"vimeo\.com/(?:video/)?(\d+)(?:/([0-9a-zA-Z]+))?[^\"'<> ]*")


def norm_vimeo(url: str) -> str | None:
    """Any Vimeo URL -> canonical https://vimeo.com/<id>/<hash> (hash optional).

    Returns None for a showcase/album URL or anything without a numeric id —
    those are not a single playable lesson video.
    """
    if not url or "vimeo.com" not in url or "showcase" in url or "/album/" in url:
        return None
    m = VIMEO.search(url)
    if not m:
        return None
    vid, h = m.group(1), m.group(2)
    if not h:
        m2 = re.search(r"[?&]h=([0-9a-zA-Z]+)", url)
        h = m2.group(1) if m2 else None
    return f"https://vimeo.com/{vid}/{h}" if h else f"https://vimeo.com/{vid}"


def decode_keap(url: str) -> str | None:
    """Recover a Keap /v2/click link's redirectUrl from its zlib+base64 payload,
    with no network. Tries both base64 alphabets; tolerates a corrupt trailing
    checksum by reading the stream incrementally. None if unrecoverable."""
    m = re.search(r"/v2/click/[^/]+/(.+)$", url)
    if not m:
        return None
    raw = m.group(1).split("?")[0].split("#")[0]
    for variant in (raw, raw.replace("-", "+").replace("_", "/")):
        s = variant + "=" * (-len(variant) % 4)
        try:
            blob = base64.b64decode(s)
        except Exception:
            continue
        # Incremental inflate so a damaged final Adler-32 still yields the JSON.
        obj = zlib.decompressobj()
        out = b""
        try:
            for i in range(0, len(blob), 16):
                out += obj.decompress(blob[i : i + 16])
            out += obj.flush()
        except zlib.error:
            pass
        m2 = re.search(rb'"redirectUrl"\s*:\s*"([^"]+)"', out)
        if m2:
            return m2.group(1).decode("utf-8", "replace")
    return None


def resolve_online(url: str, session) -> str | None:
    """Follow the tracking link's redirect chain to its final Vimeo URL."""
    try:
        r = session.get(url, allow_redirects=True, timeout=30)
    except Exception as e:
        print(f"    ! network error: {e}", file=sys.stderr)
        return None
    # The landing URL is usually the video; if not, the hash may sit in the page.
    for cand in (r.url, *(h.headers.get("location", "") for h in r.history)):
        v = norm_vimeo(cand or "")
        if v:
            return v
    return norm_vimeo(r.text or "")


def lesson_from_name(name: str) -> int | None:
    m = re.search(r"(\d+)", name)
    n = int(m.group(1)) if m else None
    return n if n and 1 <= n <= 365 else None


def video_href(html: str) -> str | None:
    """The href of the 'Watch ... Video' button, or, failing a text match, the
    first anchor that decodes/looks like a real video link."""
    anchors = ANCHOR.findall(html)
    for href, text in anchors:
        if WATCH.search(TAG.sub(" ", text)):
            return href
    return None  # caller falls back to trying every anchor


def recover_from_eml(path: Path, session) -> str | None:
    msg = email.message_from_bytes(path.read_bytes(), policy=email.policy.default)
    part = msg.get_body(preferencelist=("html",)) or msg.get_body()
    if part is None:
        return None
    html = part.get_content()

    # Prefer the explicitly-labelled watch button; else consider every anchor.
    labelled = video_href(html)
    hrefs = [labelled] if labelled else [h for h, _ in ANCHOR.findall(html)]

    # First pass, offline: any Keap link that decodes straight to a real video.
    for href in hrefs:
        if href and "/v2/click/" in href:
            v = norm_vimeo(decode_keap(href) or "")
            if v:
                return v
    # Second pass, online: follow whatever's left (infusion/appspot/showcase).
    for href in hrefs:
        if not href:
            continue
        v = resolve_online(href, session)
        if v:
            return v
    return None


def load_lessons() -> tuple[list, str]:
    text = LESSONS.read_text(encoding="utf-8")
    return json.loads(text), text


def needs_fill(l: dict) -> bool:
    v = l.get("video") or ""
    return (not v) or ("/v2/click/" in v) or ("showcase" in v)


def patch(text: str, old_field_value: str, new_url: str, number: int) -> str:
    """Replace one lesson's video value in the compact JSON, by exact match.

    Values are JSON-encoded first so escaping in the file (e.g. a corrupt
    payload ending in a backslash, stored as ``\\\\``) is matched faithfully.
    """
    old_enc = json.dumps(old_field_value, ensure_ascii=False)
    new_enc = json.dumps(new_url, ensure_ascii=False)
    needle = '"video":' + old_enc
    if text.count(needle) == 1:
        return text.replace(needle, '"video":' + new_enc)
    # Fall back to a number-anchored replace so we never touch the wrong row.
    pat = re.compile(r'(\{"number":' + str(number) + r'\b.*?"video":)' +
                     re.escape(old_enc) + r'()', re.S)
    new_text, n = pat.subn(lambda m: m.group(1) + new_enc, text, count=1)
    if n != 1:
        raise RuntimeError(f"lesson {number}: could not place video uniquely")
    return new_text


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", type=Path, default=DEFAULT_RAW,
                    help=f"raw .eml archive dir (default: {DEFAULT_RAW})")
    ap.add_argument("--links", type=Path,
                    help="CSV of lesson_number,url rows to fill from directly")
    ap.add_argument("--apply", action="store_true",
                    help="write lessons.json (default: dry run, report only)")
    args = ap.parse_args()

    lessons, text = load_lessons()
    by_num = {l["number"]: l for l in lessons}
    todo = {l["number"] for l in lessons if needs_fill(l)}
    print(f"lessons needing a video: {len(todo)}")

    session = None
    found: dict[int, str] = {}

    # 1) Manual CSV first — cheapest, most authoritative.
    if args.links:
        for row in csv.reader(args.links.open()):
            if not row or not row[0].strip().isdigit():
                continue
            n, url = int(row[0]), row[1].strip()
            v = (norm_vimeo(url)
                 or norm_vimeo(decode_keap(url) or "")
                 or (resolve_online(url, _session()) if "vimeo.com" not in url else None))
            if v and n in todo:
                found[n] = v

    # 2) The raw email archive.
    if args.raw and args.raw.is_dir():
        try:
            import requests  # only needed for redirect-following
            session = requests.Session()
            session.headers["User-Agent"] = "Mozilla/5.0 (recover_videos)"
        except ImportError:
            session = None
            print("  (note: `pip install requests` to follow non-Keap links; "
                  "Keap links still decode offline)", file=sys.stderr)
        for f in sorted(args.raw.glob("*.eml")):
            n = lesson_from_name(f.name)
            if n is None or n not in todo or n in found:
                continue
            v = recover_from_eml(f, session)
            if v:
                found[n] = v
                print(f"  lesson {n}: {v}")
    elif not args.links:
        print(f"  raw archive not found at {args.raw} — pass --raw or --links",
              file=sys.stderr)

    # Report + apply.
    still = sorted(todo - set(found))
    print(f"\nrecovered: {len(found)}   still missing: {len(still)}")
    if still:
        print("  " + ", ".join(map(str, still)))

    if not found:
        return 0
    if not args.apply:
        print("\n(dry run — re-run with --apply to write lessons.json)")
        return 0

    for n, url in found.items():
        text = patch(text, by_num[n].get("video") or "", url, n)
    json.loads(text)  # validate before writing
    LESSONS.write_text(text, encoding="utf-8")
    print(f"\nwrote {len(found)} video URLs to {LESSONS}")
    return 0


def _session():
    try:
        import requests
        s = requests.Session()
        s.headers["User-Agent"] = "Mozilla/5.0 (recover_videos)"
        return s
    except ImportError:
        return None


if __name__ == "__main__":
    raise SystemExit(main())
