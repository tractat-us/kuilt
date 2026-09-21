# Keeping the guide easy to enter

Start with a person, a task, and a problem. Introduce technical names when
the story needs them. Keep examples short; link to setup rather than repeat it.

Vale checks the README and all `Writerside/topics/*.md` in the required
`doc-citations` CI job, including docs-only changes. This tooling step reports
existing violations with `--no-exit`; the documentation follow-up fixes the
baseline and removes that flag to make violations block merges.

## Limits

| Level | Pages | Maximum grade | Words per sentence |
| --- | --- | ---: | ---: |
| Entry | Overview, Warp, Heddle | 6 | 25 |
| Practical | Install, getting started, quick start, connections, fabrics, shared data, consensus, observability, testing | 10 | 40 |
| Technical | Other guide topics, new topics by default, README body | 12 | 60 |

README and practical-guide **opening body paragraphs** also have grade-6 and
25-word limits. This covers paragraphs before the first subheading, excluding
lists, tables, and callouts. Entry pages flag a short list of specialist terms.

Edit tiers in [`.vale.ini`](../.vale.ini) and rules in
[`.vale/styles`](../.vale/styles). Add new entry pages to the Entry section.
Design archives, internal plans, and generated API docs are outside this gate.

## Reading the scores

**Flesch–Kincaid grade** estimates difficulty from sentence length and syllables.
Lower is easier. The report also shows **Flesch reading ease**: higher is easier.
Only grade is used by the rules. Neither score measures accuracy or explains jargon.

Whole-page grades exclude code, headings, and table cells. Link labels count;
link targets and Writerside source-inclusion directives do not. Sentence checks
can still flag long sentences in lists or tables. Keep API names exact; never
hide ordinary prose in backticks or tables to improve a score.

Readability is a lexical property, not something Kotlin's type system can
check. Vale supplies the markup parser; the positive controls in
[`test-vale.py`](../.github/scripts/test-vale.py) prove the gates reject dense
prose, distinguish tiers, and skip code.

## Local checks

Install **Vale 3.22.0** on your PATH. CI pins and checksum-verifies that release.
No style download or `vale sync` is needed. From the repository root:

```sh
vale --no-global README.md Writerside/topics
python3 .github/scripts/test-vale.py
python3 .github/scripts/readability.py
```

These run the gate, its regression tests, and the metrics report respectively.
CI puts the report in its job summary. `--no-global` excludes personal settings.

## Starting point

Measured with Vale 3.22.0 on revision `034c7075`. These are
whole-page scores, so the README row includes setup and reference material.

| Page | Reading grade | Reading ease |
| --- | ---: | ---: |
| Guide overview | 8.77 | 58.83 |
| Warp | 8.90 | 60.14 |
| Heddle | 9.74 | 61.03 |
| README | 10.07 | 48.99 |

For current numbers across the whole guide, run the report above.
