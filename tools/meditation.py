#!/usr/bin/env python3
"""
Derive each lesson's `meditation` string — the fuller one-or-two-line form of
today's idea that the Workbook gives you to hold during practice.

Many lessons introduce that fuller form with a cue that ends in a colon
("...should begin with this:", "Say:", "...begin with this quotation from the
text:"), followed by the idea set apart as its own short line(s). Lesson 104,
for example, opens the hourly five minutes with:

    I seek but what belongs to me in truth,
    And joy and peace are my inheritance.

We pull those set-apart lines out of the body so the reminder notification can
offer them instead of just repeating the one-line phrase twice.

This is deliberately HIGH-PRECISION, not high-recall: for a practice app a wrong
verse is far worse than none, so we only emit a meditation when it is clearly a
clean, COMPLETE verse that is a fuller form of the SAME idea as the phrase.
Everything else returns "" and the caller falls back to the phrase. The guards:

  * the couplet must follow a colon-cue that names it (begin/say/quotation/...);
  * at most two lines, each a real stated line (no "___" blanks, no "[name]"
    placeholders, no trailing colon, not a rhetorical question, not obvious
    practice instructions like "for each practice period");
  * the whole thing must END on a finished thought (. or !), so mid-sentence
    fragments from the longer Part II prayers are dropped;
  * its opening words must match the phrase, so we extend today's idea rather
    than swap in a tangential quote.

Used by finalize_lessons.py (full rebuild) and add_meditation.py (add the field
to the shipped lessons.json in place).
"""
import html
import re

TAG = re.compile(r'<[^>]+>')
# A colon-cue that introduces the stated idea.
CUE = re.compile(r'(begin|say|repeat|state|these words|as follows|quotation|request|with this)', re.I)
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


def extract_meditation(body, phrase=""):
    """Return the fuller meditation verse for this lesson, or "" if none is
    confidently a complete, additive form of the phrase."""
    if not body:
        return ""
    paras = [p for p in (_strip_tags(p) for p in body.split("\n\n")) if p]
    for i, p in enumerate(paras):
        if not (p.endswith(':') and CUE.search(p[-45:].lower())):
            continue
        lines = []
        for q in paras[i + 1:i + 4]:
            if (not q or len(q) > 100 or STOP.match(q) or q.endswith('?')
                    or q.endswith(':') or BLANK.search(q) or INSTR.search(q)):
                break
            lines.append(q)
            if len(lines) >= 2:
                break
        # Drop trailing lines that don't finish a thought (mid-sentence fragments).
        while lines and not lines[-1].rstrip().endswith(('.', '!')):
            lines.pop()
        if lines and _shares_opening(phrase, lines[0]):
            if len(lines) == 1 and _words(lines[0]) == _words(phrase):
                return ""  # identical to the phrase — adds nothing
            return "\n".join(lines)
    return ""
