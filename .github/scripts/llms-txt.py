#!/usr/bin/env python3
"""Generate `llms.txt` — the machine-readable index of kuilt's documentation.

`llms.txt` (https://llmstxt.org) is a small markdown file listing where a coding
agent should look for this project's docs. Documentation MCP servers read it in
two different ways, which is why the file lives in two places:

  * from the REPOSITORY, at one of a few static paths on the default branch
    (`llms.txt` at the root is one of them) — so the file is committed;
  * from the PUBLISHED SITE, at `<site>/llms.txt` — so the docs deploy writes
    it into `site/` beside `guide/` and `api/` (see .github/workflows/docs.yml).

Both copies come from this one script, so they cannot disagree about anything
except staleness, and staleness is what the `--check` mode exists to stop: CI
runs `--check` on every pull request (the `doc-citations` job in ci.yml) and
fails when the committed file is not what this script would write today.

A hand-maintained link list goes stale the day a module is added, so nothing
here is hand-maintained except the prose at the top. Every list is derived from
a source of truth that something else already forces to be correct:

  * `docs/agent-cookbook.md` — its own `##` headings become the family links.
  * `Writerside/kuilt.tree` — the guide's table of contents, in its own order;
    each topic's title is the `#` heading of its own file.
  * `moduleDescription()` in build-logic/src/main/kotlin/kuilt.publish.gradle.kts
    — the per-module POM descriptions. That function has no `else` fallback: a
    published module without a branch there fails Gradle at configuration time,
    so every published module necessarily has one (published ⊆ described). This
    script checks the other direction (described ⊆ the modules in
    settings.gradle.kts, none of them listed as deliberately unpublished in
    kuilt-bom/build.gradle.kts), so the two together pin the list exactly.

Usage:

    python3 .github/scripts/llms-txt.py            # rewrite llms.txt in place
    python3 .github/scripts/llms-txt.py --check    # fail if it is out of date
    python3 .github/scripts/llms-txt.py --stdout   # print it
    python3 .github/scripts/llms-txt.py --out site/llms.txt --verify-site site

`--verify-site DIR` additionally asserts that every published-site URL this file
emits exists as a file under DIR. It is what the docs deploy runs against the
assembled `site/` directory, and it is the only check on the assumptions this
script cannot settle from the source tree: that the guide and the API reference
are still served where it says they are, and that the API reference still
renders a page per module at `api/<module>/index.html` for the modules routed
there (see `module_url`). Without it, a change in either layout would publish
dead links that nothing would report.
"""

from __future__ import annotations

import argparse
import difflib
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

REPO = "tractat-us/kuilt"
RAW = f"https://raw.githubusercontent.com/{REPO}/main/"
TREE_URL = f"https://github.com/{REPO}/tree/main/"
SITE = "https://tractat-us.github.io/kuilt/"

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

COOKBOOK = "docs/agent-cookbook.md"
TOC = "Writerside/kuilt.tree"
TOPICS = "Writerside/topics"
PUBLISH_PLUGIN = "build-logic/src/main/kotlin/kuilt.publish.gradle.kts"
SETTINGS = "settings.gradle.kts"
BOM = "kuilt-bom/build.gradle.kts"

# Documents that are entry points rather than members of a generated list. Each
# is existence-checked like everything else, so a rename fails here rather than
# publishing a dead link.
START_HERE = [
    ("README.md", "README", "What kuilt is, and the Maven coordinates to add it to a build."),
    ("docs/usage.md", "Using kuilt in an application", "Setup, dependency wiring and the platform notes a consumer needs."),
]
OPTIONAL = [
    ("docs/architecture.md", "Architecture", "The design behind the contract — read this to extend kuilt, not to use it."),
    ("docs/extending-fabrics.md", "Writing a new fabric", "How to put a transport kuilt does not ship behind the same contract."),
]

SUMMARY = (
    "kuilt is a Kotlin Multiplatform library for apps whose devices talk to each other — "
    "phones, browsers, desktops and servers. It gives them one way to find each other and "
    "pass messages over whichever connection is available, shared data that stays in sync "
    "when two people change it at once or one of them was offline, and — where everyone has "
    "to agree on one order of events — a leader and a shared history. The same application "
    "code runs over a relay server, a local network, a direct link between two nearby phones "
    "or a browser connection, and over two peers or twenty."
)

DETAILS = [
    "If you are a coding agent writing networking, session or shared-state code against "
    "kuilt, read the cookbook first. It is a lookup table from the thing you are about to "
    "hand-roll — a reconnect loop, a heartbeat, a seen-ids set, a merge rule — to the kuilt "
    "primitive that already does it, with a compile-checked snippet for each.",
    "Targets: JVM, Android, iOS, macOS and wasmJs. Every module below is published to Maven "
    "Central as `us.tractat.kuilt:<module>`; import `us.tractat.kuilt:kuilt-bom` once and "
    "then declare modules without versions. The README links the current release.",
    "Links to `raw.githubusercontent.com` are markdown source; links to "
    f"`{SITE}` are the rendered guide and the generated API reference.",
    "This file is generated from the repository by `.github/scripts/llms-txt.py` — from the "
    "cookbook's own headings, the guide's table of contents and the published modules' "
    "descriptions. Edit those, then re-run it; do not edit this file by hand.",
]


def fail(message: str) -> None:
    print(f"llms-txt: {message}", file=sys.stderr)
    sys.exit(1)


def read(relative: str) -> str:
    path = os.path.join(ROOT, relative)
    if not os.path.isfile(path):
        fail(f"{relative} does not exist — it is named in .github/scripts/llms-txt.py")
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def exists(relative: str) -> bool:
    return os.path.exists(os.path.join(ROOT, relative))


# ── Anchors ──────────────────────────────────────────────────────────────────
# GitHub's heading-anchor algorithm, over the RENDERED text of a heading: drop
# inline markdown, lowercase, delete anything that is not a word character,
# space or hyphen, then turn spaces into hyphens. Reproduced here rather than
# guessed at: the cookbook links to its own sections, and `heading_anchors`
# asserts every one of those in-file targets resolves to a heading it computed.
def anchor(heading_text: str) -> str:
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading_text)  # [name](url) -> name
    text = text.replace("`", "")
    text = re.sub(r"(\*\*|__|\*)", "", text)
    text = text.lower()
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return text.replace(" ", "-")


def headings(markdown: str) -> list[tuple[int, str]]:
    """Every ATX heading, as (level, text), skipping fenced code blocks."""
    found: list[tuple[int, str]] = []
    fence: str | None = None
    for line in markdown.splitlines():
        stripped = line.strip()
        if fence is not None:
            if stripped.startswith(fence):
                fence = None
            continue
        if stripped.startswith("```") or stripped.startswith("~~~"):
            fence = stripped[:3]
            continue
        match = re.match(r"^(#{1,6})\s+(.*?)\s*$", line)
        if match:
            found.append((len(match.group(1)), match.group(2)))
    return found


def heading_anchors(markdown: str, source: str) -> dict[str, str]:
    """Map heading text -> anchor, with GitHub's duplicate suffixes applied."""
    anchors: dict[str, str] = {}
    seen: dict[str, int] = {}
    for _, text in headings(markdown):
        base = anchor(text)
        count = seen.get(base, 0)
        seen[base] = count + 1
        anchors[text] = base if count == 0 else f"{base}-{count}"
    # Self-check: the document's own in-page links must all resolve. This is what
    # keeps `anchor()` honest — a wrong slug algorithm shows up here rather than
    # as a link that silently lands at the top of the page.
    known = set(anchors.values())
    dangling = sorted(
        target
        for target in re.findall(r"\]\(#([^)]+)\)", markdown)
        if target not in known
    )
    if dangling:
        fail(
            f"{source} links to in-page anchors that match no heading: {dangling}. "
            "Either the links are broken or anchor() no longer matches how the "
            "headings are slugged."
        )
    return anchors


# ── Cookbook ─────────────────────────────────────────────────────────────────
def cookbook_families() -> list[tuple[str, str]]:
    markdown = read(COOKBOOK)
    anchors = heading_anchors(markdown, COOKBOOK)
    families = [(text, anchors[text]) for level, text in headings(markdown) if level == 2]
    if len(families) < 10:
        fail(
            f"{COOKBOOK} yielded {len(families)} `##` sections, which is too few to be "
            "right — the heading scan is reading the file wrongly."
        )
    return families


def cookbook_title() -> str:
    for level, text in headings(read(COOKBOOK)):
        if level == 1:
            return text
    fail(f"{COOKBOOK} has no `#` title")
    raise AssertionError("unreachable")


# ── Guide ────────────────────────────────────────────────────────────────────
class Topic:
    def __init__(self, file_name: str, title: str, breadcrumb: list[str]):
        self.file_name = file_name
        self.title = title
        self.breadcrumb = breadcrumb


def topic_title(file_name: str) -> str:
    markdown = read(f"{TOPICS}/{file_name}")
    for level, text in headings(markdown):
        if level == 1:
            return text
    fail(f"{TOPICS}/{file_name} has no `#` title")
    raise AssertionError("unreachable")


def guide_sections() -> tuple[str, list[tuple[str, list[Topic]]]]:
    """The guide's TOC: (start-page file, [(section title, topics)]) in TOC order."""
    tree = ElementTree.parse(os.path.join(ROOT, TOC))
    profile = tree.getroot()
    start_page = profile.get("start-page")
    if not start_page:
        fail(f"{TOC} has no start-page attribute")

    sections: list[tuple[str, list[Topic]]] = []
    loose: list[Topic] = []

    def walk(element, breadcrumb: list[str], into: list[Topic]) -> None:
        for child in element:
            if child.tag != "toc-element":
                continue
            topic = child.get("topic")
            title = child.get("toc-title")
            if topic:
                name = topic_title(topic)
                into.append(Topic(topic, name, breadcrumb))
                walk(child, breadcrumb + [name], into)
            elif title:
                walk(child, breadcrumb + [title], into)
            else:
                fail(f"{TOC} has a toc-element with neither a topic nor a toc-title")

    for child in profile:
        if child.tag != "toc-element":
            continue
        topic = child.get("topic")
        title = child.get("toc-title")
        if title:
            collected: list[Topic] = []
            walk(child, [], collected)
            if collected:
                sections.append((title, collected))
        elif topic:
            if topic != start_page:
                loose.append(Topic(topic, topic_title(topic), []))
            walk(child, [], loose)
        else:
            fail(f"{TOC} has a top-level toc-element with neither a topic nor a toc-title")

    if not sections:
        fail(f"{TOC} yielded no guide sections — the TOC shape changed")
    if loose:
        sections.insert(0, ("Guide", loose))
    return start_page, sections


# ── Modules ──────────────────────────────────────────────────────────────────
def module_groups() -> list[tuple[str, list[tuple[str, str]]]]:
    """moduleDescription()'s branches, grouped by its own `// ── name ──` comments."""
    source = read(PUBLISH_PLUGIN)
    start = source.find("fun moduleDescription(")
    if start < 0:
        fail(f"{PUBLISH_PLUGIN} has no moduleDescription() — the module list has no source")
    body = source[start:]
    end = body.find("else -> error(")
    if end < 0:
        fail(f"{PUBLISH_PLUGIN}: moduleDescription() has no `else -> error(` arm to stop at")
    body = body[:end]

    groups: list[tuple[str, list[tuple[str, str]]]] = []
    current: list[tuple[str, str]] = []
    group_name = "Modules"
    module: str | None = None
    parts: list[str] = []

    def flush() -> None:
        nonlocal module, parts
        if module is not None:
            description = " ".join(" ".join(parts).split())
            if not description:
                fail(f"{PUBLISH_PLUGIN}: no description text parsed for {module}")
            current.append((module, description))
        module, parts = None, []

    for line in body.splitlines():
        group = re.match(r"^\s*//\s*─+\s*(.*?)\s*─+\s*$", line)
        if group:
            flush()
            if current:
                groups.append((group_name, current[:]))
                current.clear()
            group_name = group.group(1)
            continue
        branch = re.match(r'^\s*"(kuilt-[a-z0-9-]+)"\s*->\s*(.*)$', line)
        if branch:
            flush()
            module = branch.group(1)
            # Only what follows `->`: the branch's own key is a string literal too.
            parts = literals(branch.group(2), module)
            continue
        if module is not None:
            parts += literals(line, module)
    flush()
    if current:
        groups.append((group_name, current[:]))

    described = [name for _, entries in groups for name, _ in entries]
    if len(described) < 30:
        fail(
            f"{PUBLISH_PLUGIN}: parsed {len(described)} module descriptions, too few to be "
            "right — moduleDescription()'s shape changed"
        )
    if len(set(described)) != len(described):
        fail(f"{PUBLISH_PLUGIN}: moduleDescription() describes the same module twice")
    check_modules_exist(described)
    return groups


def literals(line: str, module: str) -> list[str]:
    """The Kotlin string literals on one line of a moduleDescription() branch."""
    if "$" in line or "\\" in line:
        fail(
            f"{PUBLISH_PLUGIN}: the description of {module} uses a template or an escape "
            "(`$` or `\\`), which this parser does not render. Keep POM descriptions plain."
        )
    return re.findall(r'"([^"]*)"', line.split("//")[0])


def check_modules_exist(described: list[str]) -> None:
    settings = read(SETTINGS)
    included = set(re.findall(r'^include\("(:[^"]+)"\)', settings, flags=re.MULTILINE))
    unpublished = set()
    bom = read(BOM)
    block = re.search(r"val deliberatelyUnpublished = setOf\((.*?)\n\)", bom, flags=re.DOTALL)
    if not block:
        fail(f"{BOM}: no deliberatelyUnpublished set — the unpublished-module cross-check is blind")
    unpublished = set(re.findall(r'"(:[^"]+)"', block.group(1)))

    missing = sorted(name for name in described if f":{name}" not in included)
    if missing:
        fail(
            f"moduleDescription() in {PUBLISH_PLUGIN} describes modules that are not in "
            f"{SETTINGS}: {missing}. Remove the stale branch."
        )
    unpublishable = sorted(name for name in described if f":{name}" in unpublished)
    if unpublishable:
        fail(
            f"moduleDescription() describes {unpublishable}, which {BOM} lists as "
            "deliberately unpublished. One of the two is wrong."
        )


def module_url(module: str) -> str:
    """Where a reader is sent for one module, best documentation first.

    1. `<module>/module.md` — the module's own overview, in markdown, written for
       a reader rather than generated. 28 of the 44 published modules have one.
    2. The module's page in the API reference — for a module with no module.md.
       Gated on the module having a `src/commonMain`, because the convention
       plugin documents `common*` source sets and suppresses the rest, so a
       module whose whole surface lives in `jvmAndAndroidMain` gets NO page on
       the site (measured against the deployed site: every one of the 38 modules
       with a `src/commonMain` has a page; `:kuilt-otel-logback`,
       `:kuilt-otel-log4j2`, `:kuilt-otel-sdk` and `:kuilt-warp-compiler`, which
       have none, have no page — all four are caught by rule 1 anyway).
       `:kuilt-warp-runtime` is the one false negative — a page without a
       `commonMain` — and it lands on rule 1 too. The predicate is a proxy, so
       `--verify-site` is what stops a wrong one shipping as a dead link.
    3. The module's source directory — for what is left: the BOM platform and
       the KSP processor, neither of which has code a reader browses.
    """
    script = f"{module}/build.gradle.kts"
    if not exists(script):
        fail(f"{script} does not exist, but {module} has a POM description")
    if exists(f"{module}/module.md"):
        return RAW + f"{module}/module.md"
    if exists(f"{module}/src/commonMain"):
        return SITE + f"api/{module}/index.html"
    return TREE_URL + module


# ── Rendering ────────────────────────────────────────────────────────────────
def bullet(name: str, url: str, note: str = "") -> str:
    return f"- [{name}]({url})" + (f": {note}" if note else "")


def render() -> str:
    out: list[str] = ["# kuilt", "", f"> {SUMMARY}", ""]
    for paragraph in DETAILS:
        out += [paragraph, ""]

    start_page, sections = guide_sections()

    out += ["## Start here", ""]
    out.append(bullet(topic_title(start_page), RAW + f"{TOPICS}/{start_page}",
                      "The guide's opening page — what kuilt gives you, in plain language."))
    for path, name, note in START_HERE:
        read(path)
        out.append(bullet(name, RAW + path, note))
    out.append(bullet("API reference", SITE + "api/",
                      "Generated API documentation for every module, cross-linked."))
    out.append(bullet("Guide", SITE + "guide/",
                      "The rendered guide, if you would rather read it as a site than as markdown."))
    out.append("")

    title = cookbook_title()
    out += ["## Cookbook for coding agents", ""]
    out.append(bullet(title, RAW + COOKBOOK,
                      "Read this before writing networking, session or shared-state code "
                      "against kuilt: what you are about to build, and the kuilt primitive "
                      "that already does it. The links below are sections of this one file."))
    for family, slug in cookbook_families():
        out.append(bullet(family, f"{RAW}{COOKBOOK}#{slug}"))
    out.append("")

    for section, topics in sections:
        out += [f"## Guide: {section}", ""]
        for topic in topics:
            out.append(bullet(topic.title, RAW + f"{TOPICS}/{topic.file_name}",
                              " › ".join(topic.breadcrumb)))
        out.append("")

    for group, modules in module_groups():
        out += [f"## Modules: {group}", ""]
        for module, description in modules:
            out.append(bullet(module, module_url(module), description))
        out.append("")

    out += ["## Optional", ""]
    for path, name, note in OPTIONAL:
        read(path)
        out.append(bullet(name, RAW + path, note))
    out.append("")

    return "\n".join(out)


def verify_site(content: str, site_dir: str) -> None:
    missing = []
    checked = 0
    for url in re.findall(r"\]\((" + re.escape(SITE) + r"[^)#]*)", content):
        relative = url[len(SITE):]
        if relative.endswith("/") or relative == "":
            relative += "index.html"
        checked += 1
        if not os.path.isfile(os.path.join(site_dir, relative)):
            missing.append(relative)
    # A gate that checks nothing passes silently, so say so instead: if the file
    # stops linking the site at all, this mode is vacuous and its green is a lie.
    if checked == 0:
        fail(
            "llms.txt contains no published-site links, so --verify-site verified "
            "nothing. Either the generator stopped emitting them or SITE is wrong."
        )
    if missing:
        fail(
            "llms.txt links pages the assembled site does not contain: "
            + ", ".join(sorted(set(missing)))
            + f". Either the site layout changed or {SITE} is no longer how it is served; "
            "fix .github/scripts/llms-txt.py rather than publishing dead links."
        )
    print(f"llms-txt: all {checked} published-site links resolve inside {site_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate llms.txt from the repository.")
    parser.add_argument("--check", action="store_true",
                        help="exit non-zero when the committed llms.txt is out of date")
    parser.add_argument("--stdout", action="store_true", help="print instead of writing")
    parser.add_argument("--out", default=None, help="write somewhere other than ./llms.txt")
    parser.add_argument("--verify-site", default=None, metavar="DIR",
                        help="assert every published-site link exists under DIR")
    args = parser.parse_args()

    content = render()
    if args.verify_site:
        verify_site(content, args.verify_site)

    committed = os.path.join(ROOT, "llms.txt")
    if args.check:
        current = ""
        if os.path.isfile(committed):
            with open(committed, encoding="utf-8") as handle:
                current = handle.read()
        if current != content:
            diff = difflib.unified_diff(
                current.splitlines(keepends=True), content.splitlines(keepends=True),
                fromfile="llms.txt (committed)", tofile="llms.txt (generated)",
            )
            sys.stdout.writelines(diff)
            fail(
                "llms.txt is out of date. Run `python3 .github/scripts/llms-txt.py` and "
                "commit the result — it is generated from the cookbook's headings, the "
                "guide's TOC and the published modules' descriptions."
            )
        print("llms-txt: llms.txt is up to date")
        return

    if args.stdout:
        sys.stdout.write(content)
        return

    target = args.out or committed
    directory = os.path.dirname(os.path.abspath(target))
    os.makedirs(directory, exist_ok=True)
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(content)
    print(f"llms-txt: wrote {target} ({len(content.splitlines())} lines)")


if __name__ == "__main__":
    main()
