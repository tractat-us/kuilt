"""Report Vale's prose metrics; .vale.ini and the style rules enforce the limits."""

import json
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[2]


def main():
    print("| Page | Reading grade | Reading ease | Prose words |")
    print("| --- | ---: | ---: | ---: |")
    paths = [ROOT / "README.md", *sorted((ROOT / "Writerside/topics").glob("*.md"))]
    for path in paths:
        metrics = json.loads(subprocess.check_output(
            ["vale", "--no-global", f"--config={ROOT / '.vale.ini'}", "ls-metrics", str(path)],
            text=True, cwd=ROOT,
        ))
        words, sentences, syllables = (metrics[k] for k in ("words", "sentences", "syllables"))
        if not words or not sentences:
            raise SystemExit(f"No measurable prose in {path.relative_to(ROOT)}")
        grade = .39 * words / sentences + 11.8 * syllables / words - 15.59
        ease = 206.835 - 1.015 * words / sentences - 84.6 * syllables / words
        print(f"| {path.relative_to(ROOT)} | {grade:.2f} | {ease:.2f} | {words} |")


if __name__ == "__main__":
    main()
