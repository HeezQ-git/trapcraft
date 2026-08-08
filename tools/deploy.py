#!/usr/bin/env python3
"""Drop the built jar into ../modpacks/server.mrpack, then publish to Pages.

Two outputs from one source: the .mrpack the server boots from, and the
packwiz pack on GitHub Pages that players auto-update from. They MUST move
together -- Pages once sat twenty versions behind the server because only the
mrpack was being updated here, and nobody could tell from either side.

Idempotent: rewrites the zip rather than appending, so re-running replaces the
existing entry instead of leaving a stale duplicate. Run after ./gradlew build.

    python3 tools/deploy.py              # update mrpack AND publish to Pages
    python3 tools/deploy.py --local      # mrpack only, push nothing
    python3 tools/deploy.py --selftest   # check the version bumper
"""

import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
PACK = HERE.parent / "modpacks/server.mrpack"
BACKUP = HERE.parent / "modpacks/server.mrpack.pre-trapcraft"
PACKWIZ = HERE.parent / "trappack"
PAGES_URL = "https://heezq-git.github.io/trappack/pack.toml"


def main() -> None:
    gate()

    jars = sorted((HERE / "build/libs").glob("trapcraft-*.jar"))
    jars = [j for j in jars if "sources" not in j.name]
    if not jars:
        sys.exit("no jar in build/libs -- run ./gradlew build first")
    jar = jars[-1]

    if not PACK.exists():
        sys.exit(f"missing {PACK}")
    if not BACKUP.exists():
        shutil.copy2(PACK, BACKUP)
        print(f"backed up -> {BACKUP.name}")

    target = f"overrides/mods/{jar.name}"
    tmp = PACK.with_suffix(".mrpack.new")
    old_version = new_version = "?"

    with zipfile.ZipFile(PACK) as zin, \
            zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            # Drop any previous trapcraft jar so version bumps don't stack up.
            if item.filename.startswith("overrides/mods/trapcraft-"):
                continue
            if item.filename == "modrinth.index.json":
                index = json.loads(zin.read(item.filename))
                old_version = index.get("versionId", "")
                new_version = bump(old_version)
                index["versionId"] = new_version
                zout.writestr(item, json.dumps(index, indent=2))
                continue
            zout.writestr(item, zin.read(item.filename))
        zout.write(jar, target)

    shutil.move(tmp, PACK)
    print(f"deployed {jar.name} -> {PACK.name}:{target}")
    print(f"pack version {old_version} -> {new_version}")

    if "--local" in sys.argv:
        print("--local: skipped publishing to Pages")
    else:
        publish(new_version)

    print("restart the server: docker compose restart mc")


CHECKS = ["check_models.py", "check_pages.py", "check_stock.py",
          "check_shaders.py", "trip_check.py"]


def gate() -> None:
    """Refuse to ship if any checker is unhappy.

    Three times running I read past check_pages saying a guide page was over
    the limit and shipped it anyway -- an over-limit page truncates silently
    in the book, which is the exact failure that checker exists to catch. A
    warning a human can skim past is not a check; a non-zero exit that stops
    the deploy is. Override with --force if you genuinely mean it.
    """
    if "--force" in sys.argv:
        print("--force: skipping the checkers")
        return
    here = Path(__file__).resolve().parent
    for check in CHECKS:
        result = run([sys.executable, str(here / check)], check=False)
        if result.returncode != 0:
            print(f"\n{check} failed -- not shipping. Fix it, or pass --force.")
            sys.exit(1)


def publish(version: str) -> None:
    """Regenerate the packwiz pack from the mrpack and push it to Pages.

    Everything is derived from the mrpack we just wrote, so the two artifacts
    cannot disagree -- the version included.
    """
    if not (PACKWIZ / "pack.toml").exists():
        print(f"no packwiz pack at {PACKWIZ}, skipping publish")
        return

    packwiz = shutil.which("packwiz") or str(Path.home() / "go/bin/packwiz")
    if not Path(packwiz).exists():
        print("packwiz not found (go install github.com/packwiz/packwiz@latest)")
        print("mrpack is updated; Pages was NOT published")
        return

    try:
        run([sys.executable, "tools/from_mrpack.py"])
        run([packwiz, "refresh"])

        pack_toml = PACKWIZ / "pack.toml"
        pack_toml.write_text(re.sub(
            r'^version = ".*"$', f'version = "{version}"',
            pack_toml.read_text(), count=1, flags=re.M))

        run(["git", "add", "-A"])
        # Nothing staged means nothing changed; committing anyway would spam
        # the history with empty version bumps.
        if run(["git", "diff", "--cached", "--quiet"], check=False).returncode == 0:
            print("packwiz pack unchanged, nothing to publish")
            return

        run(["git",
             "-c", "user.name=HeezQ",
             "-c", "user.email=heezq.contact@gmail.com",
             "commit", "-q", "-m", f"Pack {version}"])
        run(["git", "push", "-q"])
        print(f"published {version} -> {PAGES_URL}")
        print("players get it on their next launch")
    except subprocess.CalledProcessError as error:
        # Never fail the whole deploy over publishing: the mrpack is already
        # written and the server can be restarted regardless.
        print(f"publish FAILED ({error.cmd[0]}); mrpack is fine, Pages is stale")


def run(command, check=True):
    return subprocess.run(command, cwd=PACKWIZ, check=check,
                          capture_output=True, text=True)


def bump(version: str) -> str:
    """Increment the trailing integer: 1.0.16 -> 1.0.17.

    Tolerant on purpose. Re-exporting from the Modrinth App resets versionId to
    whatever that app wrote, which may not look like ours, so anything without a
    trailing number just gains one rather than blowing up mid-deploy.
    """
    match = re.match(r"^(.*?)(\d+)$", version or "")
    if not match:
        return f"{version}.1" if version else "0.0.1"
    head, number = match.groups()
    return f"{head}{int(number) + 1}"


def _selftest() -> None:
    assert bump("1.0.16") == "1.0.17"
    assert bump("1.0.9") == "1.0.10"      # not 1.0.91
    assert bump("1.2.99") == "1.2.100"    # carries width, doesn't wrap
    assert bump("2") == "3"
    assert bump("TrapPack") == "TrapPack.1"
    assert bump("") == "0.0.1"
    print("bump() ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        _selftest()
    else:
        main()
