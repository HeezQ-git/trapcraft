#!/usr/bin/env python3
"""Fail if site/index.html no longer matches what the source would produce.

The wiki is generated, which is only worth anything if a stale one cannot
ship. That check was originally in the Pages workflow, which was the wrong
place twice over: the runner has no Pillow and, more to the point, no
Minecraft jar -- the icons come out of the Loom cache, so the page can only
ever be built on a machine that has built the mod.

So the gate lives here, next to the other five, and runs before a deploy on
the machine that actually has everything. The workflow just publishes what it
is given.

Regenerates in memory and compares; it never writes, so a failing check leaves
the tree exactly as it found it.

    python3 tools/check_wiki.py
"""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import gen_wiki  # noqa: E402


def main() -> int:
    if not gen_wiki.OUT.is_file():
        print(f"  {gen_wiki.OUT.name} has never been built -- run python3 tools/gen_wiki.py")
        return 1

    gen_wiki.gather()
    fresh = gen_wiki.build()
    on_disk = gen_wiki.OUT.read_text()

    if fresh == on_disk:
        print(f"wiki is current ({len(on_disk):,} bytes, "
              f"{len(gen_wiki.DATA['strains'])} strains, "
              f"{len(gen_wiki.DATA['jobs'])} crew jobs)")
        return 0

    print("  site/index.html is stale -- the source has moved since it was built")
    print("  run: python3 tools/gen_wiki.py && git add site/")
    return 1


if __name__ == "__main__":
    sys.exit(main())
