#!/usr/bin/env python3
"""
Brings upstream's latest main into the Pocket Wormhole+ branch and bumps the
Plus version, for .github/workflows/plus-sync.yml (it can also be run by hand
from a checkout of the plus branch).

Upstream's own version bumps conflict with the Plus package name and version
in app/build.gradle.kts and the app label in the manifest, so conflicts in
only those files are resolved by taking upstream's file and re-applying the
Plus changes. Any other conflict stops the sync, leaving the branch as it was.

Writes status=uptodate|conflict|updated (plus version, tag, conflicts) to
$GITHUB_OUTPUT when that's set, and the release notes to plus-release-notes.md.
"""
import os
import re
import subprocess
import sys

UPSTREAM = os.environ.get("UPSTREAM_URL", "https://github.com/adamkulik/pocket-wormhole")
FORCE = os.environ.get("FORCE", "false") == "true"

BUILD_FILE = "app/build.gradle.kts"
MANIFEST = "app/src/main/AndroidManifest.xml"
REBRANDED = {BUILD_FILE, MANIFEST}


def git(*args, check=True):
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed:\n{result.stdout}{result.stderr}")
    return result


def output(**values):
    for key, value in values.items():
        print(f"{key}={value}")
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as f:
            for key, value in values.items():
                f.write(f"{key}={value}\n")


def rebrand():
    """Re-apply the Plus package name and app label (idempotent)."""
    with open(BUILD_FILE) as f:
        build = f.read()
    build = re.sub(r'applicationId = "com\.pocketwormhole\.android(\.plus)?"',
                   'applicationId = "com.pocketwormhole.android.plus"', build, count=1)
    with open(BUILD_FILE, "w") as f:
        f.write(build)

    with open(MANIFEST) as f:
        manifest = f.read()
    manifest = re.sub(r'android:label="Pocket Wormhole\+?"', 'android:label="Pocket Wormhole+"', manifest, count=1)
    with open(MANIFEST, "w") as f:
        f.write(manifest)


def read_version(text):
    code = int(re.search(r"versionCode = (\d+)", text).group(1))
    name = re.search(r'versionName = "([^"]+)"', text).group(1)
    return code, name


def bump_version(upstream_ref):
    """Plus versionCode is upstream's * 100 + the Plus release on top of it."""
    up_code, up_name = read_version(git("show", f"{upstream_ref}:{BUILD_FILE}").stdout)
    with open(BUILD_FILE) as f:
        build = f.read()
    old_code, _ = read_version(build)

    if old_code // 100 == up_code:
        n = old_code % 100 + 1
    else:
        n = 1
    code = up_code * 100 + n
    name = f"{up_name}-plus.{n}"

    build = re.sub(r"versionCode = \d+", f"versionCode = {code}", build, count=1)
    build = re.sub(r'versionName = "[^"]+"', f'versionName = "{name}"', build, count=1)
    with open(BUILD_FILE, "w") as f:
        f.write(build)
    return name


def main():
    git("fetch", "-q", UPSTREAM, "main")
    upstream = git("rev-parse", "FETCH_HEAD").stdout.strip()
    before = git("rev-parse", "HEAD").stdout.strip()
    has_new = git("merge-base", "--is-ancestor", upstream, "HEAD", check=False).returncode != 0

    if not has_new and not FORCE:
        output(status="uptodate")
        return

    if has_new:
        merge = git("merge", "--no-edit", "-m", f"Merge upstream {upstream[:7]} into plus", upstream, check=False)
        if merge.returncode != 0:
            conflicts = set(git("diff", "--name-only", "--diff-filter=U").stdout.split())
            if not conflicts or not conflicts <= REBRANDED:
                git("merge", "--abort", check=False)
                output(status="conflict", conflicts=" ".join(sorted(conflicts)) or "(merge failed)")
                return
            # Only the files Plus rebrands: take upstream's, rebrand below.
            for path in conflicts:
                git("checkout", "--theirs", "--", path)
            rebrand()
            git("add", *conflicts)
            git("commit", "--no-edit")

    rebrand()
    version = bump_version(upstream)
    git("add", BUILD_FILE, MANIFEST)
    git("commit", "-m", f"Pocket Wormhole+ {version}")

    new_upstream = git("log", "--no-merges", "--format=- %s", f"{before}..{upstream}").stdout.strip()
    with open("plus-release-notes.md", "w") as f:
        f.write(f"Pocket Wormhole+ {version}, built automatically from the `plus` branch.\n\n")
        if new_upstream:
            f.write("**New from upstream Pocket Wormhole:**\n\n" + new_upstream + "\n\n")
        f.write("Installs next to the official app (package `com.pocketwormhole.android.plus`) "
                "and updates earlier Pocket Wormhole+ releases. You need your own `ftl.dat` "
                "from FTL 1.6.14; nothing from the game is included.\n")

    output(status="updated", version=version, tag=f"v{version}")


if __name__ == "__main__":
    main()
