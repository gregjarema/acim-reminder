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


# The workbook usually prescribes TWO things at once, and they must not be
# collapsed into one. Lesson 201 is the clearest case:
#
#   "Besides the time you give morning and evening, which should not be less
#    than fifteen minutes, and the hourly remembrances you make throughout
#    the day..."
#
# That's a long sitting twice a day AND a short remembrance every hour. So we
# parse a sitting (how long, how often) and, separately, whether the lesson also
# asks to be recalled hourly in between.
#
# Order matters within a sentence: "morning and evening" must beat "hourly",
# otherwise the sentence above reads as an hourly sitting.
SITTING_PATTERNS = [
    (r"morning and (?:again at )?(?:night|evening)|morning and evening"
     r"|night and morning", ("count", 2)),
    (r"twice an hour|every half[- ]hour|every thirty minutes", ("interval", 30)),
    (rf"every {NUM} (?:or {NUM} )?minutes", ("interval", None)),
    # Value is meaningless for an hourly sitting, but it must not be None:
    # None means "read the count out of a capture group", and this pattern has
    # none — which silently made every hourly lesson fall through to the next
    # rule and land on morning-and-evening.
    (r"every hour|on the hour|each hour|hourly|the hour strikes"
     r"|as often as (?:possible|you can)", ("hourly", 1)),
    (rf"{NUM} (?:or {NUM} )?(?:practice periods|practice sessions)", ("count", None)),
    (rf"(?:at least |about )?{NUM} times?(?: an?| each| per)? day", ("count", None)),
    (rf"(?:practise|practice|repeat|use it|apply it).{{0,30}}{NUM} times", ("count", None)),
    (r"\btwice a day\b", ("count", 2)),
]

# A short recollection every hour, on top of whatever sitting is prescribed.
REMEMBRANCE_RE = re.compile(
    r"hourly remembrance|the hour strikes|every hour|each hour|on the hour"
    r"|hour(?:ly)? (?:we|you)? ?remember|remember.{0,20}each hour", re.I)


def _sitting_in(sentence: str) -> tuple[str, int] | None:
    for pat, (kind, fixed) in SITTING_PATTERNS:
        m = re.search(pat, sentence, re.I)
        if not m:
            continue
        if fixed is not None:
            return kind, fixed
        vals = [num(g) for g in m.groups() if g and num(g)]
        if vals:
            return kind, max(1, round(max(vals)))
    return None


def find_frequency(text: str) -> tuple[str, int, str] | None:
    """
    How often to sit. Sentences that also name a duration are tried first —
    those are the ones actually describing the practice ("Each hour today give
    Him your tiny gift of but five minutes"), rather than prose that happens to
    mention an hour.
    """
    sentences = re.split(r"(?<=[.!?])\s+", text)
    with_duration = [s for s in sentences if "minute" in s.lower()]
    for group in (with_duration, sentences):
        for sentence in group:
            hit = _sitting_in(sentence)
            if hit:
                return hit[0], hit[1], sentence.strip()
    return None


def find_remembrance(text: str) -> str | None:
    """The sentence asking for an hourly recollection, if the lesson wants one."""
    for sentence in re.split(r"(?<=[.!?])\s+", text):
        if REMEMBRANCE_RE.search(sentence):
            return sentence.strip()
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
    cur_min, cur_kind, cur_val, cur_remember = 1, "count", 4, False
    stated_dur = stated_freq = stated_rem = 0

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

        # The second track. A lesson that already sits hourly doesn't also need
        # an hourly nudge — that's the same event.
        rem = find_remembrance(text)
        if rem:
            cur_remember = True
            stated_rem += 1
        rem_src = rem or ""
        if cur_kind == "hourly":
            cur_remember = False

        l["practiceMinutes"] = cur_min
        l["practiceKind"] = cur_kind          # hourly | interval | count
        l["practiceValue"] = cur_val          # minutes if interval, else count
        l["hourlyRemembrance"] = cur_remember
        l["practiceStated"] = bool(d or f)
        l["practiceSource"] = " ".join(s for s in (dur_src, freq_src, rem_src) if s).strip()
        l["meditationText"] = meditation_lines(l)

    from collections import Counter
    print(f"lessons: {len(lessons)}")
    print(f"  duration stated outright:  {stated_dur}  (rest inherit the previous lesson)")
    print(f"  frequency stated outright: {stated_freq}")
    print(f"  hourly remembrance stated: {stated_rem}")
    print("  resulting duration spread:", dict(Counter(l["practiceMinutes"] for l in lessons).most_common()))
    print("  resulting sitting spread:",
          dict(Counter(f'{l["practiceKind"]}:{l["practiceValue"]}' for l in lessons).most_common(8)))
    print(f"  also nudged hourly:        {sum(1 for l in lessons if l['hourlyRemembrance'])}")
    print(f"  with meditation text:      {sum(1 for l in lessons if l['meditationText'])}")

    if args.report:
        for l in lessons:
            freq = ("every hour" if l["practiceKind"] == "hourly"
                    else f'every {l["practiceValue"]}min' if l["practiceKind"] == "interval"
                    else f'{l["practiceValue"]}x/day')
            print(f'L{l["number"]:3d} | {l["practiceMinutes"]:2d}min | {freq:11s} | '
                  f'{"+hourly" if l["hourlyRemembrance"] else "       "} | '
                  f'{"stated  " if l["practiceStated"] else "inherit "} | '
                  f'{l["practiceSource"][:120]}')
        return 0

    LESSONS.write_text(json.dumps(lessons, ensure_ascii=False, separators=(",", ":")),
                       encoding="utf-8")
    print(f"wrote {LESSONS.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
