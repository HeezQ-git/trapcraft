#!/usr/bin/env python3
"""Compile our post shaders the way Minecraft will, before shipping them.

A GLSL error doesn't degrade gracefully. The pipeline fails to load, and since
the client sets the post processor from a tick handler, that surfaces as a
crash or a black screen for everyone on the pack -- with the mistake sitting in
a file nobody can read from in game.

Needs glslangValidator (brew install glslang). Minecraft resolves
`#moj_import <namespace:file.glsl>` itself, so this inlines those from the
client jar first and then compiles the result.

    python3 tools/check_shaders.py
"""

import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
SHADERS = HERE / "src/main/resources/assets/trapcraft/shaders/post"
CLIENT_JAR = Path.home() / ".gradle/caches/fabric-loom/1.21.8/minecraft-client.jar"

IMPORT = re.compile(r"^\s*#moj_import\s*<([a-z0-9_]+):([a-z0-9_./]+)>\s*$", re.M)


def resolve(source: str, jar: zipfile.ZipFile) -> str:
    """Inline #moj_import, stripping the #version line the include carries."""
    def swap(match):
        namespace, name = match.group(1), match.group(2)
        path = f"assets/{namespace}/shaders/include/{name}"
        text = jar.read(path).decode()
        # Only one #version is legal per translation unit, and ours is first.
        return "\n".join(l for l in text.splitlines()
                         if not l.strip().startswith("#version"))
    return IMPORT.sub(swap, source)


def main() -> None:
    validator = shutil.which("glslangValidator")
    if validator is None:
        sys.exit("glslangValidator not found -- brew install glslang")
    if not CLIENT_JAR.is_file():
        sys.exit(f"no client jar at {CLIENT_JAR} -- run ./gradlew build first")

    failures = 0
    with zipfile.ZipFile(CLIENT_JAR) as jar:
        for shader in sorted(SHADERS.glob("*.fsh")):
            resolved = resolve(shader.read_text(), jar)
            with tempfile.NamedTemporaryFile("w", suffix=".frag", delete=False) as tmp:
                tmp.write(resolved)
                tmp_path = tmp.name
            result = subprocess.run([validator, tmp_path],
                                    capture_output=True, text=True)
            Path(tmp_path).unlink()
            if result.returncode == 0:
                print(f"  ok    {shader.name}")
            else:
                failures += 1
                print(f"  FAIL  {shader.name}")
                for line in (result.stdout + result.stderr).splitlines():
                    if line.strip() and not line.startswith(tmp_path):
                        print(f"        {line}")

    print(f"{failures} failing shader(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
