#!/usr/bin/env python3
"""Positive controls for verifySkillBodyBudget; run from an idle kuilt worktree.

Requires JDK 21 and timeout on PATH. Temporarily edits the canonical skill and
restores its exact bytes in finally; never run concurrently with another editor
or build in this worktree. Logs remain in the printed temporary directory.
"""

from pathlib import Path
import re
import subprocess
import tempfile


def main():
    skill = Path(".claude/skills/kuilt-primitives/SKILL.md")
    stamp = Path("build/verification/verify-skill-body-budget.ok")
    original = skill.read_bytes()
    frontmatter = re.match(rb"\A---\r?\n.*?\r?\n---(?:\r?\n|$)", original, re.S)
    if frontmatter is None:
        raise SystemExit("Start with a valid skill frontmatter block.")
    prefix = original[:frontmatter.end()]
    body = original[frontmatter.end():]
    cap = 8192
    if not body.strip() or len(body) > cap:
        raise SystemExit("Start with a non-empty skill body within the limit.")
    command = ["timeout", "900", "./gradlew", "verifySkillBodyBudget",
               "--max-workers=2", "--console=plain"]
    logs = Path(tempfile.mkdtemp(prefix="kuilt-skill-body-budget-"))
    print(f"Logs: {logs}", flush=True)
    print("Command: " + " ".join(command), flush=True)

    def run(label, failure=None, expected_bytes=len(body)):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True)
        (logs / f"{label}.log").write_text(result.stdout)
        if failure is None:
            if result.returncode != 0:
                raise AssertionError(f"{label}: expected green\n{result.stdout}")
            measured = stamp.read_text().strip()
            if f"body: {expected_bytes} UTF-8 bytes; limit: {cap} bytes" not in measured:
                raise AssertionError(f"{label}: wrong stamp: {measured}")
            print(f"{label}: GREEN — {measured}", flush=True)
        else:
            line = f"{skill}: {failure}"
            if result.returncode == 0 or line not in result.stdout:
                raise AssertionError(f"{label}: expected guard failure {line!r}\n{result.stdout}")
            print(f"{label}: RED — {line}", flush=True)

    try:
        run("0-final")
        skill.write_bytes(original + b"x" * (cap + 1 - len(body)))
        run("1-over", "body is 8193 UTF-8 bytes, 1 over the 8,192 bytes (8 KiB) limit. "
            "Move explanatory prose to docs/agent-cookbook*, never drop a route.")
        skill.write_bytes(original)
        run("2-restored")
        skill.write_bytes(prefix[:prefix.rfind(b"---")] + body)
        run("3-no-closing", "opening YAML frontmatter has no closing delimiter (---).")
        skill.rename(logs / "skill-moved.md")
        run("4-missing", "missing skill file.")
        skill.write_bytes(original)
        run("5-restored")

        # The remaining error arms and an exact UTF-8 boundary: a character count
        # would pass the em-dash plant even though its byte count exceeds the cap.
        skill.write_bytes(body)
        run("6-no-opening", "no opening YAML frontmatter delimiter (---).")
        skill.write_bytes(prefix + b"\n \t\n")
        run("7-empty", "empty body after YAML frontmatter.")
        skill.write_bytes(prefix + b"x" * cap)
        run("8-exact-limit", expected_bytes=cap)
        skill.write_bytes(prefix + b"x" * (cap - 1) + "—".encode("utf-8"))
        run("9-multibyte-over", "body is 8194 UTF-8 bytes, 2 over the 8,192 bytes (8 KiB) limit. "
            "Move explanatory prose to docs/agent-cookbook*, never drop a route.")
    finally:
        skill.write_bytes(original)
    run("10-restored")


if __name__ == "__main__":
    main()
