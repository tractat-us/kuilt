"""Exercise the real Vale gate, including its file tiers and Markdown parsing."""

import json
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
PLAIN = "We share a list of jobs. Each phone takes a job and sends back its answer."
MODERATE = (
    "Each device records the work it has completed and shares the results with "
    "the rest of the group when its connection returns."
)
TECHNICAL = (
    "The system records each update and sends it to other devices, which merge "
    "the changes into a shared view when their connections return."
)
DENSE = (
    "The implementation of the aforementioned methodology necessitates considerable "
    "computational infrastructure, notwithstanding the organization's preexisting "
    "technological capabilities."
)


class ReadabilityGateTest(unittest.TestCase):
    def lint(self, path, prose):
        result = subprocess.run(
            ["vale", "--no-global", f"--config={ROOT / '.vale.ini'}",
             "--output=JSON", f"--path={path}", "--ext=.md"],
            input=prose, text=True, capture_output=True, cwd=ROOT,
        )
        self.assertIn(result.returncode, (0, 1), result.stderr + result.stdout)
        alerts = json.loads(result.stdout)
        checks = {a["Check"] for items in alerts.values() for a in items}
        return result.returncode, checks

    def test_entry_rejects_prose_that_a_technical_page_accepts(self):
        code, checks = self.lint("Writerside/topics/overview.md", MODERATE)
        self.assertEqual(code, 1)
        self.assertIn("Entry.Grade", checks)
        self.assertEqual(self.lint("Writerside/topics/warp-jobs.md", MODERATE)[0], 0)

    def test_new_guide_pages_inherit_a_gate(self):
        code, checks = self.lint("Writerside/topics/a-new-topic.md", DENSE)
        self.assertEqual(code, 1)
        self.assertIn("Technical.Grade", checks)

    def test_practical_guide_has_its_own_limit(self):
        # Grade 11.85: above the practical limit, below the technical limit.
        code, checks = self.lint("Writerside/topics/getting-started.md", TECHNICAL)
        self.assertEqual(code, 1)
        self.assertIn("Guide.Grade", checks)
        self.assertEqual(self.lint("Writerside/topics/warp-jobs.md", TECHNICAL)[0], 0)
        self.assertEqual(self.lint("Writerside/topics/getting-started.md", MODERATE)[0], 0)

    def test_each_tier_allows_one_grade_of_headroom(self):
        # Measured with Vale 3.22.0: grades 6.34, 10.82, and 12.45.
        entry = (
            "The group chooses a leader to order decisions; each device keeps a copy. "
            "If the leader goes away, the group can choose another."
        )
        guide = (
            "The node records each update and sends it to other peers, which merge "
            "the changes into a shared view when their connections return."
        )
        technical = (
            "The system records each update and sends it to other devices, which merge "
            "the changes into a shared view when their network connections return."
        )
        opening = f"# Title\n\n{entry}\n\n## Details\n\n{PLAIN}\n"
        cases = (
            ("Writerside/topics/overview.md", entry),
            ("Writerside/topics/getting-started.md", guide),
            ("Writerside/topics/warp-jobs.md", technical),
            ("README.md", opening),
            ("Writerside/topics/contract.md", opening),
        )
        for path, prose in cases:
            with self.subTest(path=path):
                self.assertEqual(self.lint(path, prose), (0, set()))

    def test_long_simple_sentence_is_rejected_at_entry(self):
        prose = "We " + "can share this work and " * 8 + "then go home."
        code, checks = self.lint("Writerside/topics/warp.md", prose)
        self.assertEqual(code, 1)
        self.assertIn("Entry.SentenceLength", checks)

    def test_readme_and_section_openings_stay_simple(self):
        for path in ("README.md", "Writerside/topics/contract.md"):
            with self.subTest(path=path):
                code, checks = self.lint(path, f"# Title\n\n{MODERATE}\n\n## Details\n\n{PLAIN}\n")
                self.assertEqual(code, 1)
                self.assertIn("Opening.Grade", checks)
                self.assertEqual(self.lint(path, f"# Title\n\n{PLAIN}\n\n## Details\n\n{MODERATE}\n")[0], 0)

    def test_markdown_code_and_link_targets_are_not_prose(self):
        prose = f"# Hello\n\n{PLAIN}\n\n```kotlin\n{DENSE}\n```\n\n"
        prose += f"`{DENSE}`\n\n[Read more](https://example.org/{'infrastructure' * 20}).\n"
        self.assertEqual(self.lint("Writerside/topics/overview.md", prose)[0], 0)

    def test_entry_avoids_unexplained_jargon(self):
        code, checks = self.lint("Writerside/topics/heddle.md", "A quorum can use a CRDT.")
        self.assertEqual(code, 1)
        self.assertIn("Entry.Jargon", checks)


if __name__ == "__main__":
    unittest.main()
