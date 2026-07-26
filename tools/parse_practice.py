#!/usr/bin/env python3
"""
parse_practice.py — Work out each lesson's prescribed practice, and write it
into app/src/main/assets/lessons.json.

The workbook tells you how long to practise and how often, and it changes
constantly: "a minute or so" in the first fortnight, "six practice periods, each
of two-minutes duration" by Lesson 25, "every hour on the hour" in the 90s.
The app used to ignore all of that and run a flat 5 minutes.

Two things make this tractable:

1. Only some lessons state a practice at all — the rest inherit. Walking 1..365
   in order and carrying the last stated practice forward is exactly how the
   workbook reads, and it's what fills the ~300 lessons that say nothing.
2. Where a lesson embeds a Part/Review introduction, that intro text is part of
   the lesson body, so a stated practice there is picked up like any other.

This is heuristic, not exact — the wording varies enormously ("three or four
practice periods", "at least twice an hour, attempting to do so every half
hour"). Every lesson records whether its practice was `stated` or `inherited`,
and the sentence it came from, so anything suspect can be checked by eye.

Usage:
  python3 tools/parse_practice.py            # rewrite lessons.json in place
  python3 tools/parse_practice.py --report   # print what was found, write nothing
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LESSONS = REPO / "app" / "src" / "main" / "assets" / "lessons.json"

WORDS = {
    "half": 0.5, "one": 1, "a": 1, "an": 1, "two": 2, "three": 3, "four": 4,
    "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    "eleven": 11, "twelve": 12, "fifteen": 15, "twenty": 20, "thirty": 30,
    "forty": 40, "forty-five": 45, "sixty": 60,
}


def num(token: str) -> float | None:
    token = (token or "").strip().lower()
    if token.isdigit():
        return float(token)
    return WORDS.get(token)


NUM = r"(\d+|half|one|a|an|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty-five|forty|sixty)"

# Longest / most specific first — the first match wins.
DURATION_PATTERNS = [
    # "each of two-minutes duration", "a full minute for each"
    rf"{NUM}[- ]minutes?\s+(?:duration|each|apiece)",
    rf"(?:a\s+)?full\s+{NUM}?\s*minutes?",
    # "for at least a minute each time", "no more than a minute or so"
    rf"(?:for|of|allowing|spend|devote|take)\s+(?:at least\s+|about\s+|some\s+|no more than\s+|not more than\s+|a full\s+)?{NUM}\s*minutes?",
    # "five minutes", "fifteen minutes"
    rf"{NUM}\s*minutes?",
]

# A range ("three to five minutes") — take the upper bound.
RANGE_RE = re.compile(rf"{NUM}\s+(?:to|or)\s+{NUM}\s*minutes?", re.I)
HALF_MINUTE_RE = re.compile(r"half a minute", re.I)
MINUTE_OR_SO_RE = re.compile(r"a minute or (?:so|two)|the usual minute", re.I)


# "every fifteen or twenty minutes" states how OFTEN to practise, not how LONG.
# Left in place it reads as a 20-minute sitting, so mask these before looking
# for a duration.
INTERVAL_PHRASE_RE = re.compile(
    rf"every\s+(?:{NUM}\s+(?:or\s+{NUM}\s+)?minutes?|half[- ]hour|hour)", re.I)


def find_duration(text: str) -> tuple[int, str] | None:
    """Minutes to practise in one period, and the sentence it came from."""
    for raw in re.split(r"(?<=[.!?])\s+", text):
        if "minute" not in raw.lower():
            continue
        sentence = INTERVAL_PHRASE_RE.sub(" ", raw)
        if "minute" not in sentence.lower():
            continue          # the only mention was the interval we just masked
        m = RANGE_RE.search(sentence)
        if m:
            hi = num(m.group(2))
            if hi:
                return max(1, round(hi)), raw.strip()
        for pat in DURATION_PATTERNS:
            m = re.search(pat, sentence, re.I)
            if m:
                v = num(m.group(1)) if m.lastindex else 1
                if v:
                    return max(1, round(v)), raw.strip()
        if HALF_MINUTE_RE.search(sentence) or MINUTE_OR_SO_RE.search(sentence):
            return 1, raw.strip()
    return None


# Frequency. "kind" is one of:
#   hourly   — once an hour through the practice window
#   interval — every N minutes through the window
#   count    — N times spread across the day
FREQ_PATTERNS = [
    (r"twice an hour|every half[- ]hour|every thirty minutes", ("interval", 30)),
    (rf"every {NUM} (?:or {NUM} )?minutes", ("interval", None)),
    (r"every hour|on the hour|hourly|each hour", ("hourly", None)),
    (r"as often as (?:possible|you can)", ("hourly", None)),
    (r"morning and (?:again at )?(?:night|evening)|morning and evening", ("count", 2)),
    (rf"{NUM} (?:or {NUM} )?(?:practice periods|practice sessions)", ("count", None)),
    (rf"(?:at least |about )?{NUM} times?(?: a| each| per)? day", ("count", None)),
    (rf"(?:practise|practice|repeat|use it|apply it).{{0,30}}{NUM} times", ("count", None)),
    (r"\btwice\b", ("count", 2)),
]


def find_frequency(text: str) -> tuple[str, int, str] | None:
    """(kind, value, source sentence). value is minutes for interval, else count."""
    for sentence in re.split(r"(?<=[.!?])\s+", text):
        low = sentence.lower()
        for pat, (kind, fixed) in FREQ_PATTERNS:
            m = re.search(pat, low, re.I)
            if not m:
                continue
            if fixed is not None:
                return kind, fixed, sentence.strip()
            # Take the largest number the pattern captured ("three or four" -> 4).
            vals = [num(g) for g in m.groups() if g and num(g)]
            if vals:
                return kind, max(1, round(max(vals))), sentence.strip()
    return None


# Some lessons ask for no sitting at all — just carry the idea and repeat it.
# Those days should show no timer, so a lesson that states a frequency, states
# no duration, and frames practice purely as repetition gets 0 minutes. Like any
# other stated practice, that carries forward until a duration turns up again.
REPETITION_ONLY_RE = re.compile(
    r"(?:merely|simply|just)\s+repeat|repeat(?:ing)?\s+(?:today'?s\s+)?(?:the\s+)?idea"
    r"|remind(?:ing)?\s+yourself|bring\s+to\s+mind", re.I)


def is_repetition_only(text: str) -> bool:
    return bool(REPETITION_ONLY_RE.search(text)) and "minute" not in text.lower()


def strip_tags(html: str) -> str:
    return re.sub(r"<[^>]+>", "", html or "")


def meditation_lines(lesson: dict) -> str:
    """
    The lines to hold during practice — the notification's subtext.

    Prefer the explicit `meditation` field where an earlier pass produced one,
    then the italic passages the emails marked up, and fall back to the lesson's
    one-line idea. Italic runs are joined with newlines so a multi-line verse
    survives intact.
    """
    if lesson.get("meditation"):
        return lesson["meditation"]
    ital = re.findall(r"<i>(.*?)</i>", lesson.get("body") or "", re.S)
    lines = []
    for chunk in ital:
        t = re.sub(r"\s+", " ", strip_tags(chunk)).strip(" “”\"")
        # Skip stray one-word emphasis; we want verses, not italicised nouns.
        if len(t.split()) >= 4 and t not in lines:
            lines.append(t)
    return "\n".join(lines[:3]) if lines else (lesson.get("phrase") or "")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="print, don't write")
    args = ap.parse_args()

    lessons = json.loads(LESSONS.read_text(encoding="utf-8"))
    lessons.sort(key=lambda l: l["number"])

    # Sensible opening default: the workbook's first fortnight is "a minute or
    # so, three or four times". Overwritten as soon as Lesson 1 states its own.
    cur_min, cur_kind, cur_val = 1, "count", 4
    stated_dur = stated_freq = 0

    for l in lessons:
        text = strip_tags(l.get("body") or "")

        d = find_duration(text)
        if d:
            cur_min, dur_src = d
            stated_dur += 1
        elif is_repetition_only(text):
            # Explicitly a repetition day: no sitting, so no timer.
            cur_min, dur_src = 0, "repetition only — no timed practice"
            stated_dur += 1
        else:
            dur_src = ""

        f = find_frequency(text)
        if f:
            cur_kind, cur_val, freq_src = f
            stated_freq += 1
        else:
            freq_src = ""

        l["practiceMinutes"] = cur_min
        l["practiceKind"] = cur_kind          # hourly | interval | count
        l["practiceValue"] = cur_val          # minutes if interval, else count
        l["practiceStated"] = bool(d or f)
        l["practiceSource"] = (dur_src + (" " if dur_src and freq_src else "") + freq_src).strip()
        l["meditationText"] = meditation_lines(l)

    print(f"lessons: {len(lessons)}")
    print(f"  duration stated outright:  {stated_dur}  (rest inherit the previous lesson)")
    print(f"  frequency stated outright: {stated_freq}")
    from collections import Counter
    print("  resulting duration spread:", dict(Counter(l["practiceMinutes"] for l in lessons).most_common()))
    print("  resulting frequency spread:",
          dict(Counter(f'{l["practiceKind"]}:{l["practiceValue"]}' for l in lessons).most_common(8)))
    print(f"  with meditation text:      {sum(1 for l in lessons if l['meditationText'])}")

    if args.report:
        for l in lessons[:0] or [x for x in lessons if x["practiceStated"]][:15]:
            print(f'  L{l["number"]:3d}  {l["practiceMinutes"]:2d}min '
                  f'{l["practiceKind"]}:{l["practiceValue"]}  <- {l["practiceSource"][:90]}')
        return 0

    LESSONS.write_text(json.dumps(lessons, ensure_ascii=False, separators=(",", ":")),
                       encoding="utf-8")
    print(f"wrote {LESSONS.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
