#!/usr/bin/env python3
"""Check and run the merge-gating probes from one manifest (#2639).

Membership is exact (source declarations, Gradle class selectors, and XML methods).
Budget checking is deliberately lexical: Kotlin has no effect type proving that an
arbitrary test body and its transitive callees never consult a clock. A typed
harness would still accept lambdas that call timed helpers; a type-resolving linter
could identify overloads but cannot establish that transitive absence either.
Scan the entire member file, including local helpers and imports, for visible
budgets. Positive fixtures pin this bounded backstop, not a proof: helper calls,
aliases declared elsewhere, string templates, reflection and alternate clock APIs
can hide timing. Argument-bearing wait/join calls are rejected conservatively;
this scan cannot resolve their overloads.
Review remains necessary. The shared runConcurrencyStress harness cap is allowed;
unbounded first/await calls do not introduce a private wall-clock budget.
"""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path('.github/capability-probes.json')
SUFFIX = 'CapabilityConcurrencyTest'


# Mask comments and literals, preserving line numbers. Kotlin block comments nest.
def kotlin_code(text):
    pattern = re.compile(r'//[^\n]*|/\*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    result = []
    cursor = 0
    while match := pattern.search(text, cursor):
        result.append(text[cursor:match.start()])
        end = match.end()
        if match.group() == '/*':
            depth = 1
            while depth and end < len(text):
                token = re.search(r'/\*|\*/', text[end:])
                if token is None:
                    end = len(text)
                    break
                depth += 1 if token.group() == '/*' else -1
                end += token.end()
        result.append(re.sub(r'[^\n]', ' ', text[match.start():end]))
        cursor = end
    result.append(text[cursor:])
    return ''.join(result)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def check(root):
    manifest = json.loads((root / MANIFEST).read_text())
    require(isinstance(manifest, list) and manifest, 'manifest must be a nonempty list')
    candidates = {}
    for source in sorted(root.glob('*/src/**/*Test*/**/*.kt')):
        code = kotlin_code(source.read_text())
        package = re.search(r'^\s*package\s+([\w.]+)', code, re.M)
        for name in re.findall(r'\bclass\s+(\w*' + SUFFIX + r')\b', code):
            require(package is not None, f'{source}: candidate has no package')
            fqcn = package[1] + '.' + name
            require(fqcn not in candidates, f'duplicate candidate: {fqcn}')
            candidates[fqcn] = (source.relative_to(root).as_posix(), code)
    classes = [entry['class'] for entry in manifest]
    require(len(classes) == len(set(classes)), 'duplicate manifest class')
    require(set(classes) == set(candidates),
            f'candidate membership mismatch: missing={sorted(set(classes) - candidates.keys())}, '
            f'unexpected={sorted(candidates.keys() - set(classes))}')
    for entry in manifest:
        fqcn = entry['class']
        source, code = candidates[fqcn]
        require(source == entry['source'], f'{fqcn}: source moved: {source}')
        require(source.startswith('kuilt-core/src/jvmTest/'),
                f'{fqcn}: gate runner supports kuilt-core jvmTest only')
        tests = entry['tests']
        require(tests and len(tests) == len(set(tests)), f'{fqcn}: empty/duplicate test membership')
        # These probes use ordinary, non-parameterized @Test methods. Reject drift
        # instead of deriving the expected XML rows from whatever happened to run.
        methods = re.findall(r'@(?:kotlin\.test\.)?Test\s+(?:public\s+)?fun\s+(\w+)\s*\(', code)
        require(Counter(methods) == Counter(tests),
                f'{fqcn}: test membership mismatch: expected={tests}, declared={methods}')
        budgets = [
            r'\b(?:CyclicBarrier|withTimeout|withTimeoutOrNull|TimeUnit)\b',
            r'\b(?:await|join|wait)\s*\(\s*[^\s)]',
            r'\b(?:get|tryLock|awaitTermination|awaitAdvanceInterruptibly)\s*\([^()]*,',
            r'\b(?:sleep|delay|nanoTime|currentTimeMillis)\s*\(',
        ]
        for pattern in budgets:
            match = re.search(pattern, code)
            require(match is None, f'{source}: visible wall-clock budget: '
                    f'{match.group() if match else ""}')
    return manifest


def check_xml(manifest, directory):
    expected = {(entry['class'], method) for entry in manifest for method in entry['tests']}
    rows = []
    for path in sorted(directory.glob('TEST-*.xml')):
        tree = ET.parse(path)
        for suite in tree.iter('testsuite'):
            for status in ('skipped', 'failures', 'errors'):
                require(int(suite.get(status, '0')) == 0, f'{path}: suite has {status}')
        for case in tree.iter('testcase'):
            key = (case.get('classname'), case.get('name'))
            require(not any(case.find(status) is not None for status in ('skipped', 'failure', 'error')),
                    f'{path}: unsuccessful XML row {key}')
            rows.append(key)
    require(Counter(rows) == Counter(expected),
            f'XML membership mismatch: expected={sorted(expected)}, actual={sorted(rows)}')
    print(f'XML membership OK: {len(rows)} executed, successful test rows')


def run(root, manifest, gradle_args):
    directory = root / 'kuilt-core/build/test-results/jvmTest'
    # A failed/empty invocation must not inherit evidence from a previous run.
    for path in directory.glob('TEST-*.xml'):
        path.unlink()
    command = ['./gradlew', ':kuilt-core:jvmTest', '-Pconcurrency.stress.tests=true']
    for entry in manifest:
        command.extend(['--tests', entry['class']])
    command.extend(gradle_args)
    result = subprocess.run(command, cwd=root)
    check_xml(manifest, directory)
    require(result.returncode == 0, f'Gradle failed: exit {result.returncode}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--xml-dir', type=Path)
    parser.add_argument('gradle_args', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.discover(str(Path(__file__).parent), 'test_capability_probes.py')
        require(suite.countTestCases() > 0, 'no policy fixtures discovered')
        require(unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful(), 'policy fixtures failed')
    manifest = check(ROOT)
    print(f'manifest OK: {len(manifest)} gating classes', flush=True)
    if args.xml_dir:
        check_xml(manifest, args.xml_dir)
    if args.run:
        run(ROOT, manifest, [arg for arg in args.gradle_args if arg != '--'])


if __name__ == '__main__':
    main()
