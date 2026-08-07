#!/usr/bin/env python3
"""Flag guide-book pages that will silently truncate.

A written book page holds about 14 lines of about 19 characters. Overflow
doesn't error, wrap, or scroll -- it just isn't drawn, so a page that grew one
sentence too long looks fine in the source and loses its last paragraph in
game. Invisible until someone reads the book and asks why it stops mid-word.

Two page shapes exist in TrapGuide and BOTH have to be measured:

    pages.add(page(Text.empty().append(body("..."))))        inline
    MutableText t = ...; for (...) t.append(body("..."));    accumulated
    pages.add(page(t...))

The first version of this script only understood the inline shape, so the
accumulated ones -- the tier tables, whose length is driven by an array and is
therefore exactly the kind of thing that grows -- were silently skipped and
reported as passing. Anything that can't be measured now says so out loud
rather than counting as a pass.

    python3 tools/check_pages.py
"""

import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "src/main/java/dev/heezq/trapcraft"
SOURCE = SRC / "TrapGuide.java"

MAX_LINES = 14
MAX_COLS = 19

# Width charged to an interpolated expression. A bare constant is a number, so
# three digits covers it. Method calls vary far too much for one number --
# squadOf() returns "4 pillager, 3 vind, ravager" while emeralds() returns a
# single digit -- and charging them all the worst case produced false failures
# on pages that are actually fine. Known helpers get their real widths.
WIDTH_CONSTANT = 3
WIDTH_CALL_DEFAULT = 6
CALL_WIDTHS = {
    "squadOf": 28,          # "4 pillager, 3 vind, ravager"
    "display": 6,           # grade and strain names
    "emeralds": 2,
    "cooldownMinutes": 2,
    "potency": 4,
}
# Pages this close to the limit are reported but not failed. Kept at 1 -- i.e.
# only pages sitting exactly on the limit -- because this book is written
# dense throughout and flagging anything looser lit up half of it, which is
# the fastest way to teach someone to ignore a warning.
TIGHT = 1

CALL_NAME = re.compile(r'(\w+)\s*\(\s*\)?')
# Iterations assumed for a loop whose bound can't be resolved.
LOOP_FALLBACK = 6

TEXT_CALL = re.compile(r'\b(?:title|body|hint|item|warn)\s*\(')
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
ARRAY = re.compile(r'\bint\[\]\s+(\w+)\s*=\s*\{([^}]*)\}')
FOR_BOUND = re.compile(r'for\s*\([^;]*;[^<]*<\s*([\w.]+)\.length')


def array_lengths() -> dict[str, int]:
    """Resolve `SomeClass.ARRAY.length` loop bounds from the actual sources."""
    lengths = {}
    for path in SRC.glob("*.java"):
        for name, body in ARRAY.findall(path.read_text()):
            lengths[name] = len([p for p in body.split(",") if p.strip()])
    return lengths


def interpolation_width(gap: str) -> int:
    """How wide the expression spliced between two literals is likely to be."""
    if "(" not in gap:
        return WIDTH_CONSTANT
    names = CALL_NAME.findall(gap)
    return max((CALL_WIDTHS.get(name, WIDTH_CALL_DEFAULT) for name in names),
               default=WIDTH_CALL_DEFAULT)


def literals(fragment: str) -> str:
    """Concatenate string literals, charging interpolations their width."""
    parts = []
    cursor = 0
    for match in STRING.finditer(fragment):
        gap = fragment[cursor:match.start()]
        if parts and "+" in gap:
            parts.append("x" * interpolation_width(gap))
        parts.append(match.group(1).replace("\\n", "\n"))
        cursor = match.end()
    return "".join(parts)


def line_count(text: str) -> int:
    total = 0
    # Trailing blank lines cost nothing: every page body ends in "\n\n" for
    # spacing, and blank space falling off the bottom of a page loses no
    # content. Counting them charged almost every page a phantom extra line.
    for paragraph in text.rstrip("\n").split("\n"):
        if not paragraph:
            total += 1
            continue
        width = 0
        total += 1
        for word in paragraph.split(" "):
            step = len(word) + (1 if width else 0)
            if width + step > MAX_COLS:
                total += 1
                width = len(word)
            else:
                width += step
    return total


def pages(text: str, lengths: dict[str, int]) -> list[tuple[int, str, bool]]:
    """Walk the file, accumulating text until each page is committed.

    Returns (line number, rendered text, was-estimated) per page.
    """
    found = []
    pending = ""
    estimated = False
    multiplier = 1
    loop_end_indent = None
    # Paren depth of the pages.add(page(...)) statement currently being read.
    # Tracking this is what separates "another .append() on the open page"
    # from "a new MutableText buffer being started for the NEXT page" -- those
    # two look identical line-by-line, and guessing between them silently
    # charged one page's text to the page before it.
    open_depth = 0

    for number, line in enumerate(text.split("\n"), start=1):
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())

        if loop_end_indent is not None and stripped == "}" and indent <= loop_end_indent:
            multiplier = 1
            loop_end_indent = None

        bound = FOR_BOUND.search(stripped)
        if bound:
            name = bound.group(1).split(".")[-1]
            multiplier = lengths.get(name, LOOP_FALLBACK)
            if name not in lengths:
                estimated = True
            loop_end_indent = indent
            continue

        has_text = bool(TEXT_CALL.search(stripped))
        opens_page = "pages.add(page(" in stripped

        if has_text or opens_page or open_depth > 0:
            chunk = literals(stripped) * (multiplier if has_text else 1)

            if opens_page:
                found.append([number, pending + chunk, estimated])
                pending = ""
                estimated = False
                open_depth = stripped.count("(") - stripped.count(")")
            elif open_depth > 0:
                found[-1][1] += chunk
                open_depth += stripped.count("(") - stripped.count(")")
            else:
                pending += chunk

    return [(n, t, e) for n, t, e in found]


def main() -> None:
    text = SOURCE.read_text()
    lengths = array_lengths()

    problems = []
    tight = []
    checked = 0

    for number, rendered, was_estimated in pages(text, lengths):
        checked += 1
        lines = line_count(rendered)
        note = "  (loop size estimated)" if was_estimated else ""
        if lines > MAX_LINES:
            problems.append(f"  OVER  TrapGuide.java:{number}: "
                            f"~{lines} lines, {MAX_LINES} fit{note}")
        elif lines > MAX_LINES - TIGHT:
            tight.append(f"  tight TrapGuide.java:{number}: "
                         f"~{lines}/{MAX_LINES} lines{note}")

    for problem in problems + tight:
        print(problem)
    print(f"{checked} pages, {len(problems)} over the limit, {len(tight)} tight")
    print("loop bounds: "
          + ", ".join(f"{k}={v}" for k, v in sorted(lengths.items())))
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
