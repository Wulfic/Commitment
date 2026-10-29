"""The release notes of one version: its CHANGELOG.md entry's body, as GitHub renders a release body.

    python -I scripts/release-notes.py <version>      # prints the notes of ## <version>
    python -I scripts/release-notes.py --self-test

A release body turns every newline into a line break, so the CHANGELOG's wrapped lines are joined: a list item
("- ") and its indented continuation lines become one line, a paragraph becomes one line, and blank lines between
paragraphs stay. The entry is the "## <version>" heading whose first word is exactly the version (1.0.1 isn't
1.0.10), up to the next "## " heading.

Exit codes: 0 printed · 1 the CHANGELOG has no entry for the version, or it is empty · 2 bad arguments.
Standard library only; run it with python -I.
"""
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


class NotesError(Exception):
    """No usable entry for the version: exit 1."""


def entry(lines, version):
    """The body lines of the version's entry, without the heading and the blank lines around it."""
    body = None
    for line in lines:
        if line.startswith("## "):
            if body is not None:
                break
            words = line[3:].split()
            if words and words[0] == version:
                body = []
        elif body is not None:
            body.append(line.rstrip())
    if body is None:
        raise NotesError(f"CHANGELOG.md has no '## {version}' entry")
    while body and not body[0]:
        body.pop(0)
    while body and not body[-1]:
        body.pop()
    if not body:
        raise NotesError(f"CHANGELOG.md's '## {version}' entry is empty")
    return body


def notes(lines, version):
    """The entry with each list item and paragraph on one line."""
    paragraphs = []
    current = []
    for line in entry(lines, version):
        if not line:
            if current:
                paragraphs.append(current)
                current = []
        elif line.startswith("- ") or not current:
            current.append(line)
        else:
            current[-1] += " " + line.strip()
    if current:
        paragraphs.append(current)
    return "\n\n".join("\n".join(p) for p in paragraphs) + "\n"


def self_test():
    changelog = [
        "# Changelog", "", "## 1.0.10", "", "- not this one", "",
        "## 1.0.1 (2026-10-29)", "", "Jar names say what they run on,", "and nothing else changed.", "",
        "- one item", "  wrapped here", "- two", "", "## 1.0.0", "", "- old",
    ]
    failures = []

    def check(name, got, want):
        if got != want:
            failures.append(f"{name}: got {got!r}, want {want!r}")

    check("joins a paragraph and each item, keeps the blank line",
          notes(changelog, "1.0.1"),
          "Jar names say what they run on, and nothing else changed.\n\n- one item wrapped here\n- two\n")
    check("1.0.10 is not 1.0.1, and the entry stops at the next heading", notes(changelog, "1.0.10"), "- not this one\n")
    check("the last entry runs to the end", notes(changelog, "1.0.0"), "- old\n")
    for name, version, lines in [("a missing version", "2.0.0", changelog),
                                 ("an empty entry", "1.0.2", ["## 1.0.2", "", "## 1.0.1", "- x"]),
                                 ("a version prefix", "1.0", changelog)]:
        try:
            notes(lines, version)
            failures.append(f"{name}: no error")
        except NotesError:
            pass
    real = (REPO / "CHANGELOG.md").read_text(encoding="utf-8").splitlines()
    newest = next(line[3:].split()[0] for line in real if line.startswith("## "))
    if not notes(real, newest).strip():
        failures.append(f"CHANGELOG.md's newest entry {newest} gives no notes")
    for failure in failures:
        print(f"release-notes self-test: FAIL {failure}", file=sys.stderr)
    print(f"release-notes self-test: {'PASS' if not failures else 'FAIL'} ({7 - len(failures)}/7)")
    return 1 if failures else 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if len(argv) != 1 or not argv[0] or argv[0].startswith("-"):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    try:
        lines = (REPO / "CHANGELOG.md").read_text(encoding="utf-8").splitlines()
        # Bytes, so Windows' text-mode stdout doesn't turn each \n into \r\n.
        sys.stdout.buffer.write(notes(lines, argv[0]).encode("utf-8"))
    except (OSError, NotesError) as e:
        print(f"release-notes: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
