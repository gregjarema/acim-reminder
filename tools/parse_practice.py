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
import importlib.util
import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LESSONS = REPO / "app" / "src" / "main" / "assets" / "lessons.json"


def _load_meditation_module():
    spec = importlib.util.spec_from_file_location(
        "med", Path(__file__).resolve().parent / "meditation.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


med = _load_meditation_module()

# Hand-verified corrections for lessons whose wording defeats the heuristic
# parser below — confirmed by reading the lesson's own text, not derived from
# it. Applied last, after the heuristic pass, so a future re-run of this
# script (e.g. to pick up a new field elsewhere) can never silently revert
# them back to the heuristic's guess. Keys are whichever of practiceMinutes /
# practiceKind / practiceValue / hourlyRemembrance / remembranceEveryMinutes
# the correction touches; anything not listed keeps the heuristic's value.
OVERRIDES: dict[int, dict] = {
    # "four five-minute periods" — the parser read the "at least a minute"
    # aside instead.
    47: {"practiceMinutes": 5, "practiceKind": "count", "practiceValue": 4},
    # "four five-minute periods"; practiced "whenever you can" per the text,
    # not on an hourly schedule — count, not hourly, and no reminders.
    49: {"practiceMinutes": 5, "practiceKind": "count", "practiceValue": 4,
         "hourlyRemembrance": False},
    # "three ten-minute periods", "five or six times an hour" for the
    # remembrance in between.
    91: {"practiceMinutes": 10, "practiceKind": "count", "practiceValue": 3,
         "hourlyRemembrance": True, "remembranceEveryMinutes": 10},
    # "five minutes... as often during the day as possible" — hourly, not
    # the parser's 1-minute guess.
    107: {"practiceMinutes": 5, "practiceKind": "hourly", "practiceValue": 1,
          "hourlyRemembrance": False},
    # "a quarter of an hour... morning and evening", remembrances "at least a
    # minute as each quarter of an hour passes by".
    122: {"practiceMinutes": 15, "practiceKind": "count", "practiceValue": 2,
          "hourlyRemembrance": True, "remembranceEveryMinutes": 15},
    # "devote a half an hour" — a single extended period, not two 15s, plus
    # the hourly "Let me remember I am one with God" remembrance.
    124: {"practiceMinutes": 30, "practiceKind": "count", "practiceValue": 1,
          "hourlyRemembrance": True},
    # "Three times today... give ten minutes", plus an hourly remembrance.
    125: {"practiceMinutes": 10, "practiceKind": "count", "practiceValue": 3,
          "hourlyRemembrance": True},
    # "ten minutes, three times" plus an hourly remembrance.
    128: {"practiceMinutes": 10, "practiceKind": "count", "practiceValue": 3,
          "hourlyRemembrance": True},
    # "Three times today, at times most suitable for silence, give ten
    # minutes" plus the hourly "be still a moment" remembrance.
    129: {"practiceMinutes": 10, "practiceKind": "count", "practiceValue": 3,
          "hourlyRemembrance": True},
    # "six times, five minutes" plus an hourly remembrance.
    130: {"practiceMinutes": 5, "practiceKind": "count", "practiceValue": 6,
          "hourlyRemembrance": True},
    # "ten minutes, three times" plus an hourly remembrance.
    131: {"practiceMinutes": 10, "practiceKind": "count", "practiceValue": 3,
          "hourlyRemembrance": True},
    # "fifteen-minute periods, twice" plus an hourly remembrance.
    132: {"practiceMinutes": 15, "practiceKind": "count", "practiceValue": 2,
          "hourlyRemembrance": True},
    # "give ten minutes to these thoughts... which we will conclude today at
    # night as well" — ten minutes, morning and night — plus "as every hour
    # of the day slips by" for the remembrance. The parser instead read the
    # rhetorical "Is not a minute of the hour worth the giving..." as a
    # literal one-minute-hourly instruction. Its two verses each run four
    # <i> runs long (one line short of the sitting's — the italic-harvest
    # fallback's 3-line cap was cutting the first mid-clause), so the exact
    # text is worth stating outright rather than trusting either extractor:
    137: {"practiceMinutes": 10, "practiceKind": "count", "practiceValue": 2,
          "hourlyRemembrance": True,
          "meditationText": "When I am healed I am not healed alone.\n"
                             "And I would share my healing with the world,\n"
                             "that sickness may be banished from the mind of\n"
                             "God's one Son, Who is my only Self.",
          "remembranceText": "When I am healed I am not healed alone.\n"
                              "And I would bless my brothers, for I would\n"
                              "be healed with them, as they are healed with me."},
    # "spend five minutes" on waking and "the last five minutes of our
    # waking day" before sleep, plus "as every hour passed, we have declared
    # our choice again" in between. The parser read the "each hour in
    # between" as the practice itself (hourly, no separate remembrance)
    # rather than a second track alongside the two 5-minute sittings.
    138: {"practiceMinutes": 5, "practiceKind": "count", "practiceValue": 2,
          "hourlyRemembrance": True},
}

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
    # "two longer practice periods", "two quiet periods" — an adjective between
    # the number and the noun defeated the pattern above, which is how Lesson 70
    # ("two longer practice periods... ten to fifteen minutes") ended up
    # inheriting an hourly sitting instead of stating its own two.
    (rf"{NUM}\s+(?:\w+\s+){{1,2}}(?:practice periods|practice sessions|periods)",
     ("count", None)),
    (rf"(?:at least |about )?{NUM} times?(?: an?| each| per)? day", ("count", None)),
    (rf"(?:practise|practice|repeat|use it|apply it).{{0,30}}{NUM} times", ("count", None)),
    (r"\btwice a day\b", ("count", 2)),
]

# A short recollection every hour, on top of whatever sitting is prescribed.
REMEMBRANCE_RE = re.compile(
    r"hourly remembrance|the hour strikes|every hour|each hour|on the hour"
    r"|hour(?:ly)? (?:we|you)? ?remember|remember.{0,20}each hour"
    # Not every lesson says "hourly". Many ask for short, frequent recollection
    # in exactly these words — Lesson 70's "the short and frequent practice
    # periods today, remind yourself..." is the pattern.
    r"|short and frequent|frequent(?:ly)? practice|as often as (?:possible|you can)"
    r"|remembrances? (?:you make )?throughout the day", re.I)


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
    # Review days end with bare labels -- "On the hour:", "On the half hour:" --
    # naming which idea to repeat when. Those say how often to REMEMBER, not how
    # long to sit, and reading them as the sitting turned Lesson 111 into
    # seventeen five-minute sittings. Too short to be an instruction: skip them
    # and let the sitting inherit.
    # The label runs into the line it introduces ("On the hour:\n\nMiracles are
    # seen in light."), so drop the label itself rather than the sentence.
    sentences = [re.sub(r"(?i)\b(?:on|at) the (?:half[- ])?hour\s*:", " ", x)
                 for x in sentences]
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


# --- Review days (Review III, Lessons 111-120) ----------------------------
#
# A review day is unlike any single lesson. It carries TWO one-line thoughts,
# and the workbook's Review III introduction prescribes a format all its own:
#
#   "Devote five minutes twice a day... to considering the thoughts that are
#    assigned... Use one on the hour, and the other one a half an hour later."
#
# So the practice is: a five-minute sitting morning and evening (count, 2),
# with a brief remembrance in between that alternates the two thoughts — one on
# the hour, the other on the half hour. The generic duration/frequency
# heuristics can't express two-thoughts-alternating, so review days are handled
# here in full and skip the inheritance machinery below entirely.
#
# Detected by the two labels the assignment ends with; only Review III uses
# them, so this targets exactly Lessons 111-120.
REVIEW_RE = re.compile(
    r"On the hour:\s*(.+?)\s*On the half hour:\s*(.+?)\s*$",
    re.S | re.I)


def extract_review(body: str) -> tuple[str, str] | None:
    """The (on-the-hour, on-the-half-hour) thoughts of a review day, or None."""
    plain = strip_tags(body or "")
    if "on the half hour" not in plain.lower():
        return None
    m = REVIEW_RE.search(plain)
    if not m:
        return None
    hour = re.sub(r"\s+", " ", m.group(1)).strip()
    half = re.sub(r"\s+", " ", m.group(2)).strip()
    if not hour or not half:
        return None
    return hour, half


# --- Review IV, Lessons 141-150 --------------------------------------------
#
# A different review format from Review III's. Each day carries a shared
# central theme — stated only once, in Lesson 141's own introduction, not
# repeated in 142-150's bodies — held during two 5-minute sittings (morning
# and evening), plus two numbered daily ideas that alternate through the
# hourly remembrances between:
#
#   "Begin each day with time devoted to the preparation of your mind...
#    let this thought alone engage it... My mind holds only what I think
#    with God. Five minutes with this thought will be enough..."
#   "...merely read each of the two ideas assigned to you to be reviewed
#    that day... Each hour of the day, bring to your mind the thought with
#    which the day began, and spend a quiet moment with it. Then repeat the
#    two ideas you practice for the day..."
#
# So: meditationText is the constant central theme (the timed sitting);
# hourIdea/halfIdea are that day's own two numbered ideas, which alternate
# in the hourly remembrances the same way Review III's do.
REVIEW_IV_LESSONS = range(141, 151)
REVIEW_IV_THEME = "My mind holds only what I think with God."
NUMBERED_IDEA_RE = re.compile(r"\((\d+)\)\s*<i>(.*?)</i>", re.S)


def extract_review_iv(body: str) -> tuple[str, str] | None:
    """That day's (first, second) numbered idea for a Review IV lesson, or
    None if the body doesn't have the expected pair."""
    ideas = NUMBERED_IDEA_RE.findall(body or "")
    if len(ideas) < 2:
        return None
    first = strip_tags(ideas[0][1]).strip()
    second = strip_tags(ideas[1][1]).strip()
    if not first or not second:
        return None
    return first, second


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
    lines = lines[:3]
    # A verse can run past this three-line cap (Lesson 137's, for one, is
    # four <i> runs long) — better to show the complete sentence(s) that fit
    # than cut one off mid-clause ("...that sickness may be banished from the
    # mind of"). Drop a trailing fragment that doesn't finish a thought,
    # same as the cued-verse extractor in meditation.py does.
    while lines and not lines[-1].rstrip().endswith(('.', '!', '?')):
        lines.pop()
    return "\n".join(lines) if lines else (lesson.get("phrase") or "")


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

    review_days = 0
    review_iv_days = 0
    for l in lessons:
        text = strip_tags(l.get("body") or "")

        # Review days set their own regime in full and don't take part in the
        # duration/frequency inheritance — deliberately leaving cur_* untouched
        # so the lesson after the review inherits from before it, as it always
        # did.
        review = extract_review(l.get("body") or "")
        l["hourIdea"] = review[0] if review else ""
        l["halfIdea"] = review[1] if review else ""
        # Set only for Review IV (141-150) below: the constant theme held
        # during the sitting, shown as the day's heading (wallpaper, main
        # title) in place of stacking the two alternating hourly ideas —
        # unlike Review III, where those two thoughts ARE the whole practice
        # and belong in the heading.
        l["reviewTheme"] = ""
        if review:
            review_days += 1
            l["practiceMinutes"] = 5
            l["practiceKind"] = "count"          # a sitting morning and evening
            l["practiceValue"] = 2
            l["hourlyRemembrance"] = True         # plus the hour/half-hour thoughts
            l["durationStated"] = True
            l["frequencyStated"] = True
            l["practiceStated"] = True
            l["practiceSource"] = ("Review III: five minutes twice a day; one idea "
                                   "on the hour, the other on the half hour.")
            # Both thoughts, so the sitting and the meditation notification hold
            # the pair the day is reviewing.
            l["meditationText"] = l["hourIdea"] + "\n" + l["halfIdea"]
            # The hour/half-hour split already covers the hourly track; there's
            # no separate remembrance verse to add on top of it.
            l["remembranceText"] = ""
            if l["number"] in OVERRIDES:
                l.update(OVERRIDES[l["number"]])
            continue

        if l["number"] in REVIEW_IV_LESSONS:
            review_iv = extract_review_iv(l.get("body") or "")
            if review_iv:
                review_iv_days += 1
                l["hourIdea"], l["halfIdea"] = review_iv
                l["practiceMinutes"] = 5
                l["practiceKind"] = "count"        # a sitting morning and evening
                l["practiceValue"] = 2
                l["hourlyRemembrance"] = True       # plus the two ideas alternating hourly
                l["durationStated"] = True
                l["frequencyStated"] = True
                l["practiceStated"] = True
                l["practiceSource"] = ("Review IV: five minutes twice a day on the shared "
                                       "central theme; the day's two numbered ideas "
                                       "alternate in the hourly remembrances.")
                # The sitting holds the constant central theme, not today's two
                # ideas — those alternate in the hourly reminders instead (see
                # ReminderReceiver, which reads hourIdea/halfIdea for a review day).
                l["meditationText"] = REVIEW_IV_THEME
                l["remembranceText"] = ""
                # Drives idea() to show the constant theme as the day's heading
                # instead of stacking the two alternating ideas.
                l["reviewTheme"] = REVIEW_IV_THEME
                if l["number"] in OVERRIDES:
                    l.update(OVERRIDES[l["number"]])
                continue

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
        if cur_kind == "hourly" and cur_min >= 10:
            cur_kind, cur_val = "count", 2
            cur_remember = True

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
        # Recorded separately: a lesson can state its duration while silently
        # inheriting its frequency, and one combined flag hides exactly that.
        l["durationStated"] = bool(d) or (dur_src != "")
        l["frequencyStated"] = bool(f)
        l["practiceStated"] = bool(d or f)
        l["practiceSource"] = " ".join(s for s in (dur_src, freq_src, rem_src) if s).strip()
        l["meditationText"] = meditation_lines(l)
        # The shorter verse — when the lesson gives one — meant for the hourly
        # remembrance rather than the timed sitting. See tools/meditation.py:
        # the FIRST cued verse in the body is the sitting's; this is the LAST
        # distinct one. Most lessons don't give a separate hourly verse at
        # all, in which case this stays "" and the app falls back to
        # meditationText for both.
        l["remembranceText"] = med.extract_remembrance(
            l.get("body") or "", l.get("phrase") or "", l["meditationText"])

        if l["number"] in OVERRIDES:
            l.update(OVERRIDES[l["number"]])

    from collections import Counter
    print(f"lessons: {len(lessons)}")
    print(f"  duration stated outright:  {stated_dur}  (rest inherit the previous lesson)")
    print(f"  frequency stated outright: {stated_freq}")
    print(f"  hourly remembrance stated: {stated_rem}")
    print(f"  with a separate hourly remembrance verse: "
          f"{sum(1 for l in lessons if l['remembranceText'])}")
    print("  resulting duration spread:", dict(Counter(l["practiceMinutes"] for l in lessons).most_common()))
    print("  resulting sitting spread:",
          dict(Counter(f'{l["practiceKind"]}:{l["practiceValue"]}' for l in lessons).most_common(8)))
    print(f"  also nudged hourly:        {sum(1 for l in lessons if l['hourlyRemembrance'])}")
    print(f"  with meditation text:      {sum(1 for l in lessons if l['meditationText'])}")
    print(f"  review days (hour/half):   {review_days}")
    print(f"  review IV days (141-150):  {review_iv_days}")

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
