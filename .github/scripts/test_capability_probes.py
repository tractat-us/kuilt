"""Positive controls: each malformed fixture must fail an assertion, never a clock."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('capability_probes', Path(__file__).with_name('capability-probes.py'))
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class CapabilityProbePolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'kuilt-core/src/jvmTest/kotlin/example/ExampleCapabilityConcurrencyTest.kt'
        self.source.parent.mkdir(parents=True)
        self.code = 'package example\nclass ExampleCapabilityConcurrencyTest {\n@Test fun works() { ready.await() }\n}'
        self.source.write_text(self.code)
        self.manifest = [{
            'source': self.source.relative_to(self.root).as_posix(),
            'class': 'example.ExampleCapabilityConcurrencyTest',
            'tests': ['works'],
        }]
        manifest_path = self.root / policy.MANIFEST
        manifest_path.parent.mkdir(parents=True)
        manifest_path.write_text(json.dumps(self.manifest))
        self.xml_dir = self.root / 'kuilt-core/build/test-results/jvmTest'
        self.xml_dir.mkdir(parents=True)

    def xml(self, body='', name='works[jvm]', classname='example.ExampleCapabilityConcurrencyTest'):
        return f'<testcase classname="{classname}" name="{name}">{body}</testcase>'

    def write_xml(self, rows=None, attributes=''):
        (self.xml_dir / 'TEST-example.xml').write_text(
            f'<testsuite {attributes}>{self.xml() if rows is None else rows}</testsuite>')

    def test_valid_manifest_and_xml(self):
        self.write_xml()
        self.assertEqual(self.manifest, policy.check(self.root))
        policy.check_xml(self.manifest, self.xml_dir)

    def test_missing_or_renamed_member(self):
        for code in ('', self.code.replace('ExampleCapabilityConcurrencyTest', 'ExampleConcurrencyTest')):
            with self.subTest(code=code):
                self.source.write_text(code)
                with self.assertRaisesRegex(AssertionError, 'candidate membership mismatch.*missing='):
                    policy.check(self.root)

    def test_moved_source(self):
        self.source.rename(self.source.with_name('Moved.kt'))
        with self.assertRaisesRegex(AssertionError, 'source moved'):
            policy.check(self.root)

    def test_unexpected_candidate_in_another_module(self):
        extra = self.root / 'kuilt-conformance/src/jvmTest/kotlin/New.kt'
        extra.parent.mkdir(parents=True)
        extra.write_text('package example\nclass NewCapabilityConcurrencyTest { @Test fun works() {} }')
        with self.assertRaisesRegex(AssertionError, 'unexpected=.*NewCapabilityConcurrencyTest'):
            policy.check(self.root)

    def test_changed_method_membership(self):
        for code in (self.code.replace('works()', 'renamed()'),
                     self.code.replace('ready.await()', '') + '\n@Test fun added() {}'):
            with self.subTest(code=code):
                self.source.write_text(code)
                with self.assertRaisesRegex(AssertionError, 'test membership mismatch'):
                    policy.check(self.root)

    def test_visible_timing_budgets(self):
        for budget in ('CyclicBarrier(2)', 'withTimeout(30) {}', 'withTimeoutOrNull(30) {}',
                       'barrier.await(30, TimeUnit.SECONDS)', 'latch.await(timeout, unit)',
                       'latch.await(\n timeout,\n unit\n)', 'future.get(timeout, unit)',
                       'Thread.sleep(30)', 'worker.join(30)', 'System.nanoTime()', 'delay(30)'):
            with self.subTest(budget=budget):
                self.source.write_text(self.code.replace('ready.await()', budget))
                with self.assertRaisesRegex(AssertionError, 'visible wall-clock budget'):
                    policy.check(self.root)

    def test_import_alias_and_local_helper_are_scanned(self):
        for addition in ('\nimport java.util.concurrent.CyclicBarrier as Barrier',
                         '\nfun helper() { latch.await(30, unit) }'):
            with self.subTest(addition=addition):
                self.source.write_text(self.code + addition)
                with self.assertRaisesRegex(AssertionError, 'visible wall-clock budget'):
                    policy.check(self.root)

    def test_comments_and_literals_are_not_budgets(self):
        self.source.write_text(self.code.replace('ready.await()', 'ready.await(); map.get(key)')
                               + '\n/* outer /* CyclicBarrier */ withTimeout(1) */'
                               + '\n// barrier.await(30, unit)\nval prose = "withTimeout(30)"')
        policy.check(self.root)

    def test_missing_unexpected_duplicate_and_renamed_xml_rows(self):
        for rows in ('', self.xml(name='renamed[jvm]'), self.xml(name='works'), self.xml() * 2,
                     self.xml() + self.xml(classname='example.UnexpectedConcurrencyTest')):
            with self.subTest(rows=rows):
                self.write_xml(rows)
                with self.assertRaisesRegex(AssertionError, 'XML membership mismatch'):
                    policy.check_xml(self.manifest, self.xml_dir)

    def test_absent_xml(self):
        with self.assertRaisesRegex(AssertionError, 'XML membership mismatch'):
            policy.check_xml(self.manifest, self.xml_dir)

    def test_skipped_failed_and_errored_xml(self):
        for status in ('skipped', 'failure', 'error'):
            with self.subTest(status=status):
                self.write_xml(self.xml(f'<{status}/>'))
                with self.assertRaisesRegex(AssertionError, 'unsuccessful XML row'):
                    policy.check_xml(self.manifest, self.xml_dir)
        self.write_xml(attributes='skipped="1"')
        with self.assertRaisesRegex(AssertionError, 'suite has skipped'):
            policy.check_xml(self.manifest, self.xml_dir)

    def test_run_selects_manifest_classes_and_checks_fresh_xml(self):
        def gradle(command, cwd):
            self.assertEqual(self.root, cwd)
            self.assertEqual(['./gradlew', ':kuilt-core:jvmTest', '-Pconcurrency.stress.tests=true',
                              '--tests', self.manifest[0]['class'], '--max-workers=2'], command)
            self.write_xml()
            return policy.subprocess.CompletedProcess(command, 0)
        with patch.object(policy.subprocess, 'run', side_effect=gradle):
            policy.run(self.root, self.manifest, ['--max-workers=2'])

    def test_run_cannot_reuse_stale_xml(self):
        self.write_xml()
        with patch.object(policy.subprocess, 'run', return_value=policy.subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(AssertionError, 'XML membership mismatch'):
                policy.run(self.root, self.manifest, [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
