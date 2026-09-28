#!/usr/bin/env python3
"""
Bump version and release lambda-stringer.

Steps performed by default:
  1. Validate CHANGELOG.md has content under [Unreleased]
  2. Bump version in pom.xml, README.md, CHANGELOG.md
  3. Run tests
  4. Build fat jar (target/lambda-stringer.jar)
  5. Deploy to Maven Central (mvn deploy -P release)
  6. git commit + tag
  7. git push
  8. GitHub release (gh CLI)
"""

import re
import sys
import subprocess
import shutil
import argparse
from datetime import datetime
from pathlib import Path
from typing import Optional, Tuple


class Releaser:
    def __init__(self, root: Path):
        self.root       = root
        self.pom        = root / "pom.xml"
        self.readme     = root / "README.md"
        self.changelog  = root / "CHANGELOG.md"
        self.backup_dir = root / ".release-backup"
        self._backed_up = False

    # ------------------------------------------------------------------
    # Version helpers
    # ------------------------------------------------------------------

    def current_version(self) -> str:
        m = re.search(r'<version>([\d.]+)</version>', self.pom.read_text())
        if not m:
            raise ValueError("Could not find version in pom.xml")
        return m.group(1)

    @staticmethod
    def _parse(v: str) -> Tuple[int, ...]:
        parts = v.split(".")
        if len(parts) < 2:
            raise ValueError(f"Unexpected version format: {v}")
        return tuple(int(p) for p in parts)

    def bump_major(self, v: str) -> str:
        major, *_ = self._parse(v)
        return f"{major + 1}.0"

    def bump_minor(self, v: str) -> str:
        parts = self._parse(v)
        if len(parts) == 2:
            major, minor = parts
            return f"{major}.{minor + 1}"
        major, minor, *_ = parts
        return f"{major}.{minor + 1}.0"

    def bump_patch(self, v: str) -> str:
        parts = self._parse(v)
        if len(parts) == 2:
            major, minor = parts
            return f"{major}.{minor}.1"
        major, minor, patch, *_ = parts
        return f"{major}.{minor}.{patch + 1}"

    # ------------------------------------------------------------------
    # File updates
    # ------------------------------------------------------------------

    def _update_file(self, path: Path, old: str, new: str, label: str):
        content = path.read_text()
        if old not in content:
            print(f"  ⚠ '{old}' not found in {label}, skipping")
            return
        path.write_text(content.replace(old, new))
        print(f"  ✓ {label}")

    def update_pom(self, old: str, new: str):
        content = self.pom.read_text()
        updated = content.replace(f"<version>{old}</version>",
                                  f"<version>{new}</version>", 1)
        self.pom.write_text(updated)
        print("  ✓ pom.xml")

    def update_readme(self, old: str, new: str):
        """Replace every occurrence of the old version string in README.md."""
        content = self.readme.read_text()
        updated = content.replace(old, new)
        if updated == content:
            print("  ⚠ README.md: no occurrences of old version found")
        else:
            self.readme.write_text(updated)
            print("  ✓ README.md")

    def update_changelog(self, new: str):
        """Move [Unreleased] content to a new dated release section."""
        if not self.changelog.exists():
            print("  ⚠ CHANGELOG.md not found, skipping")
            return
        today = datetime.now().strftime("%Y-%m-%d")
        content = self.changelog.read_text()
        replacement = (
            f"## [Unreleased]\n\n"
            f"## [{new}] — {today}"
        )
        updated = re.sub(r"## \[Unreleased\]", replacement, content, count=1)
        self.changelog.write_text(updated)
        print("  ✓ CHANGELOG.md")

    # ------------------------------------------------------------------
    # Changelog validation
    # ------------------------------------------------------------------

    def changelog_unreleased_content(self) -> str:
        """Return the text under [Unreleased], empty string if none."""
        if not self.changelog.exists():
            return ""
        m = re.search(r"## \[Unreleased\]\s*\n(.*?)(?=\n## \[|$)",
                      self.changelog.read_text(), re.DOTALL)
        return (m.group(1).strip() if m else "")

    def changelog_version_content(self, version: str) -> str:
        """Return the text under a specific released version section."""
        if not self.changelog.exists():
            return ""
        pattern = rf"## \[{re.escape(version)}\][^\n]*\n(.*?)(?=\n## \[|$)"
        m = re.search(pattern, self.changelog.read_text(), re.DOTALL)
        if not m:
            return ""
        lines = []
        pending_header = None
        for line in m.group(1).strip().splitlines():
            if line.startswith("#"):
                pending_header = line
            elif line.strip():
                if pending_header:
                    lines.append(pending_header)
                    pending_header = None
                lines.append(line)
        return "\n".join(lines)

    # ------------------------------------------------------------------
    # Backup / restore
    # ------------------------------------------------------------------

    def backup(self):
        self.backup_dir.mkdir(exist_ok=True)
        for src, name in [(self.pom, "pom.xml"), (self.readme, "README.md"),
                          (self.changelog, "CHANGELOG.md")]:
            if src.exists():
                shutil.copy2(src, self.backup_dir / name)
        self._backed_up = True
        print("  ✓ backups created")

    def restore(self):
        if not self._backed_up or not self.backup_dir.exists():
            return
        print("\n⚠  Restoring files from backup...")
        for name, dest in [("pom.xml", self.pom), ("README.md", self.readme),
                            ("CHANGELOG.md", self.changelog)]:
            src = self.backup_dir / name
            if src.exists():
                shutil.copy2(src, dest)
                print(f"  ✓ restored {dest.name}")

    def cleanup_backup(self):
        if self.backup_dir.exists():
            shutil.rmtree(self.backup_dir)

    # ------------------------------------------------------------------
    # Shell commands
    # ------------------------------------------------------------------

    def run(self, cmd: list, desc: str, check: bool = True,
            cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
        print(f"\n→ {desc}")
        print(f"  $ {' '.join(str(c) for c in cmd)}")
        result = subprocess.run(cmd, cwd=(cwd or self.root),
                                capture_output=True, text=True)
        if result.returncode != 0 and check:
            print(f"✗ {desc} failed")
            print(result.stdout[-2000:] if result.stdout else "")
            print(result.stderr[-2000:] if result.stderr else "")
            self.restore()
            sys.exit(1)
        print(f"  ✓ done")
        return result

    # ------------------------------------------------------------------
    # Build / deploy
    # ------------------------------------------------------------------

    def run_tests(self):
        self.run(["mvn", "clean", "test", "--no-transfer-progress"], "Run tests")

    def build(self):
        self.run(["mvn", "clean", "package", "-DskipTests",
                  "--no-transfer-progress"], "Build fat jar")
        jar = self.root / "target" / "lambda-stringer.jar"
        if not jar.exists():
            print(f"✗ expected {jar} to exist after build")
            self.restore()
            sys.exit(1)
        size_kb = jar.stat().st_size / 1024
        print(f"  jar size: {size_kb:.1f} KiB")

    def deploy(self):
        self.run(["mvn", "clean", "deploy", "-P", "release",
                  "--no-transfer-progress"], "Deploy to Maven Central")

    # ------------------------------------------------------------------
    # Git
    # ------------------------------------------------------------------

    def git_commit(self, version: str):
        self.run(["git", "add", "pom.xml", "README.md", "CHANGELOG.md"],
                 "Stage files")
        self.run(["git", "commit", "-m", f"release {version}"], "Commit")

    def git_tag(self, version: str):
        self.run(["git", "tag", "-a", f"v{version}", "-m", f"Release {version}"],
                 f"Tag v{version}")

    def git_push(self):
        self.run(["git", "push"], "Push commits")
        self.run(["git", "push", "--tags"], "Push tags")

    # ------------------------------------------------------------------
    # GitHub release
    # ------------------------------------------------------------------

    def github_release(self, version: str):
        tag = f"v{version}"
        jar = self.root / "target" / "lambda-stringer.jar"

        notes = self.changelog_version_content(version)
        if not notes:
            notes = f"Release {version}"
        notes += (
            f"\n\n## Use\n\n"
            f"```sh\n"
            f"java -javaagent:lambda-stringer.jar -jar your-app.jar\n"
            f"```\n\n"
            f"[Full documentation](https://github.com/parttimenerd/lambda-stringer#readme)"
        )

        notes_file = self.root / ".release-notes.md"
        notes_file.write_text(notes)
        try:
            assets = [f"{jar}#lambda-stringer.jar"] if jar.exists() else []
            cmd = (["gh", "release", "create", tag,
                    "--title", f"Release {version}",
                    "--notes-file", str(notes_file)]
                   + assets)
            self.run(cmd, f"Create GitHub release {tag}")
        finally:
            notes_file.unlink(missing_ok=True)


# ------------------------------------------------------------------
# CLI
# ------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Bump version and release lambda-stringer",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--major",  action="store_true", help="Bump major version")
    group.add_argument("--minor",  action="store_true", help="Bump minor version [default]")
    group.add_argument("--patch",  action="store_true", help="Bump patch version")

    parser.add_argument("--dry-run",           action="store_true", help="Show what would happen, make no changes")
    parser.add_argument("--skip-tests",        action="store_true", help="Skip mvn test")
    parser.add_argument("--no-deploy",         action="store_true", help="Skip Maven Central deploy")
    parser.add_argument("--no-push",           action="store_true", help="Skip git push")
    parser.add_argument("--no-github-release", action="store_true", help="Skip GitHub release")

    args = parser.parse_args()

    root     = Path(__file__).resolve().parent
    releaser = Releaser(root)

    current = releaser.current_version()
    print(f"Current version: {current}")

    if args.major:
        new_version = releaser.bump_major(current)
    elif args.patch:
        new_version = releaser.bump_patch(current)
    else:
        new_version = releaser.bump_minor(current)

    print(f"New version:     {new_version}")

    do_deploy  = not args.no_deploy
    do_push    = not args.no_push
    do_github  = not args.no_github_release

    # Validate changelog (always, even dry-run — it's a pre-flight check)
    unreleased = releaser.changelog_unreleased_content()
    if len(unreleased) < 20:
        print("\n❌ CHANGELOG.md must have content under [Unreleased] before releasing.")
        print("   Add your changes, then re-run.")
        sys.exit(1)

    if args.dry_run:
        print("\n=== DRY RUN — no changes will be made ===")
        print(f"\n  pom.xml, README.md: {current} → {new_version}")
        print("  CHANGELOG.md: [Unreleased] → versioned section")
        if not args.skip_tests: print("  mvn clean test")
        print("  mvn clean package -DskipTests")
        if do_deploy:           print("  mvn clean deploy -P release")
        print(f"  git commit + tag v{new_version}")
        if do_push:             print("  git push + git push --tags")
        if do_github:           print(f"  gh release create v{new_version}")
        return

    print(f"\nThis will release {current} → {new_version}. Continue? [y/N] ", end="")
    if input().lower() not in ("y", "yes"):
        print("Aborted.")
        sys.exit(0)

    try:
        print("\n=== Backup ===")
        releaser.backup()

        print("\n=== Update version files ===")
        releaser.update_pom(current, new_version)
        releaser.update_readme(current, new_version)
        releaser.update_changelog(new_version)

        if not args.skip_tests:
            releaser.run_tests()

        releaser.build()

        if do_deploy:
            print("\nReady to deploy to Maven Central? [y/N] ", end="")
            if input().lower() not in ("y", "yes"):
                print("Skipping deploy.")
                do_deploy = False
            else:
                releaser.deploy()

        print("\n=== Git ===")
        releaser.git_commit(new_version)
        releaser.git_tag(new_version)
        if do_push:
            releaser.git_push()

        if do_github:
            releaser.github_release(new_version)

        releaser.cleanup_backup()

    except KeyboardInterrupt:
        print("\n\n⚠  Interrupted — restoring files")
        releaser.restore()
        sys.exit(1)
    except Exception as e:
        print(f"\n\n❌ Unexpected error: {e}")
        releaser.restore()
        raise

    print("\n" + "=" * 60)
    print(f"✓ Released {new_version}")
    print("=" * 60)
    print(f"  ✓ pom.xml, README.md, CHANGELOG.md updated")
    print(f"  {'✓' if not args.skip_tests else '⊘'} tests")
    print(f"  ✓ target/lambda-stringer.jar built")
    print(f"  {'✓' if do_deploy else '⊘'} Maven Central")
    print(f"  ✓ git commit + tag v{new_version}")
    print(f"  {'✓' if do_push else '⊘'} pushed")
    print(f"  {'✓' if do_github else '⊘'} GitHub release")
    if do_github:
        print(f"\n  https://github.com/parttimenerd/lambda-stringer/releases/tag/v{new_version}")
    if do_deploy:
        print(f"  https://central.sonatype.com/artifact/me.bechberger/lambda-stringer/{new_version}")


if __name__ == "__main__":
    main()
