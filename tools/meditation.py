#!/usr/bin/env python3
"""
Derive each lesson's `meditation` and `remembranceText` strings — the fuller
forms of today's idea that the Workbook gives you to hold during the timed
sitting, and separately to recall on the hour.

Many lessons introduce the sitting's fuller form with a cue that ends in a
colon ("...should begin with this:", "Say:", "...begin with this quotation
from the text:"), followed by the idea set apart as its own short line(s).
Lesson 104, for example, opens the longer practice periods with:

    I seek but what belongs to me in truth,
    And joy and peace are my inheritance.

and later gives a second, usually shorter, cued verse for the hourly
remembrance:

    I seek but what belongs to me in truth.
    God's gifts of joy and peace are all I want.

We pull both set-apart verses out of the body: the FIRST cued verse is the
sitting's meditation, the LAST DISTINCT one (when there is one) is what the
hourly reminder should show, instead of both notifications repeating the same
text or — worse — the sitting showing the hourly's shorter form because an
earlier cue was missed. (That mix-up was real: lessons 73 and 74's shipped
`meditation` field used to be their *shorter* form, because the true opening
cue phrase — "...tell yourself with gentle firmness and quiet certainty:" —
falls more than 45 characters before its colon, and the narrower search used
to look only at the last 45.)

This is deliberately HIGH-PRECISION, not high-recall: for a practice app a wrong
verse is far worse than none, so we only emit a verse when it is clearly a
clean, COMPLETE form of the SAME idea as the phrase. Everything else returns ""
and the caller falls back to the phrase (or, for the reminder, to the sitting's
own text). The guards, for the sitting verse and each later cued verse alike:

  * it must follow a colon-cue that names it (begin/say/quotation/...);
  * at most three lines, each a real stated line (no "___" blanks, no "[name]"
    placeholders, no trailing colon, not a rhetorical question, not obvious
    practice instructions like "for each practice period") — three, not two,
    because some verses reach us as three separate <i> runs, one per wrapped
    line of the original email, rather than one per sentence;
  * the whole thing must END on a finished thought (. or !), so mid-sentence
    fragments from the longer Part II prayers are dropped;
  * its opening words must match the phrase, so we extend today's idea rather
    than swap in a tangential quote. The sitting verse additionally must not
    be identical to the phrase alone (that would add nothing); the hourly
    verse MAY be identical to the phrase — lessons often do collapse the
    reminder back to exactly the phrase (Lesson 110's is just "I am as God
    created me.", the phrase itself).

Used by finalize_lessons.py (full rebuild), add_meditation.py (add/refresh
`meditation` in the shipped lessons.json in place), and parse_practice.py
(sets `remembranceText`).
"""
import html
import re

TAG = re.compile(r'<[^>]+>')
# A colon-cue that introduces the stated idea. Searched over the WHOLE cue
# paragraph, not just the tail before the colon — a cue like "...tell
# yourself with gentle firmness and quiet certainty:" names itself with "tell
# yourself" well before the colon, and a narrower window missed it entirely,
# silently falling through to a later (wrong) cue instead.
CUE = re.compile(r'(begin|say|repeat|state|these words|as follows|quotation|request|with this|remind)', re.I)
# Lines that resume instruction rather than continue the verse.
STOP = re.compile(
    r'^(then|so\b|so,|now\b|now,|what\b|close\b|repeat\b|ask\b|do\b|we\b|you\b|'
    r'here\b|for\b|when\b|if\b|notice|begin|say\b|this\b|these\b|remember|let us|'
    r'afterward|through|nor\b|yet\b|dwell|our\b|between|during|conclude|should)', re.I)
# Fill-in blanks / placeholders that only make sense in the app's body.
BLANK = re.compile(r'(_{2,}|\[|\])')
# Specific practice-instruction phrases (none appear in the verse couplets).
INSTR = re.compile(
    r'(practice period|a minute|minute or|will be sufficient|sufficient for|'
    r'go on to|close your eyes|open your eyes|throughout the day|as often as|'
    r'each hour|several minutes|five minutes|spend a|devote|hourly)', re.I)
WORD = re.compile(r"[a-z0-9']+")
# A verse can arrive as up to this many separate wrapped-line fragments.
MAX_LINES = 3


def _strip_tags(s):
    return html.unescape(TAG.sub('', s)).strip()


def _words(s):
    return WORD.findall(s.lower())


def _shares_opening(phrase, line1):
    """True when phrase and the verse's first line begin with the same idea —
    i.e. one word-sequence is a prefix of the other (>=3 shared leading words)."""
    a, b = _words(phrase), _words(line1)
    if len(a) < 3 or len(b) < 3:
        return False
    n = min(len(a), len(b))
    k = 0
    while k < n and a[k] == b[k]:
        k += 1
    return k == n


def _candidates(body, phrase=""):
    """All cue-introduced, complete verses in the body that extend the
    phrase, in the order they appear. Each is (paragraph_index, verse_text).
    Does not filter out a verse identical to the phrase — callers decide
    whether that's wanted (see extract_meditation vs extract_remembrance)."""
    if not body:
        return []
    # Paragraphs, then the lines within them. A verse is sometimes two
    # paragraphs and sometimes one paragraph broken across two or three
    # lines — the emails do both — and either way each line is a line of
    # the verse.
    paras = [line
             for para in body.split("\n\n")
             for line in (_strip_tags(para) or "").split("\n")
             if line.strip()]
    results = []
    for i, p in enumerate(paras):
        if not (p.endswith(':') and CUE.search(p.lower())):
            continue
        lines = []
        for q in paras[i + 1:i + 1 + MAX_LINES]:
            if (not q or len(q) > 100 or STOP.match(q) or q.endswith('?')
                    or q.endswith(':') or BLANK.search(q) or INSTR.search(q)):
                break
            lines.append(q)
            if len(lines) >= MAX_LINES:
                break
        # Drop trailing lines that don't finish a thought (mid-sentence fragments).
        while lines and not lines[-1].rstrip().endswith(('.', '!')):
            lines.pop()
        if lines and _shares_opening(phrase, lines[0]):
            results.append((i, "\n".join(lines)))
    return results


def extract_meditation(body, phrase=""):
    """Return the fuller meditation verse for the timed sitting, or "" if
    none is confidently a complete, additive form of the phrase."""
    for _, text in _candidates(body, phrase):
        lines = text.split("\n")
        if len(lines) == 1 and _words(lines[0]) == _words(phrase):
            continue  # identical to the phrase alone — adds nothing here
        return text
    return ""


def extract_remembrance(body, phrase="", sitting_verse=""):
    """Return the shorter verse meant for the hourly remembrance — the LAST
    cued verse in the body that differs from the sitting's own text — or ""
    when the lesson doesn't clearly give the two separately. Unlike the
    sitting verse, this MAY be identical to the phrase alone: many lessons'
    hourly reminder collapses back to exactly the day's one-line idea."""
    cands = [text for _, text in _candidates(body, phrase) if text != sitting_verse]
    return cands[-1] if cands else ""
