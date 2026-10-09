import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class HostValidationPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        match = re.search(r"testOptions\s*\{(.*?)\n    lint\s*\{", build, re.S)
        if match is None:
            raise AssertionError("Android unit-test configuration is missing")
        cls.configuration = match.group(1)

    def test_unit_test_execution_has_a_thirty_minute_deadline(self):
        self.assertRegex(
            self.configuration,
            r"it\.timeout\.set\(Duration\.ofMinutes\(30\)\)",
        )

    def test_test_classes_do_not_share_a_persistent_worker(self):
        self.assertRegex(self.configuration, r"it\.forkEvery\s*=\s*1(?:L)?\b")

    def test_isolation_preserves_one_fork_and_one_gibibyte(self):
        self.assertRegex(self.configuration, r"it\.maxParallelForks\s*=\s*1\b")
        self.assertRegex(self.configuration, r'it\.maxHeapSize\s*=\s*"1g"')

    def test_probe_checks_effective_deadline_before_waiting(self):
        probe = (ROOT / "app/src/test/java/app/gamenative/library/canonical/"
                 "CanonicalHostTimeoutProbeTest.kt").read_text(encoding="utf-8")
        guard = probe.find("requireVerifiedDeadline(System.getProperty(")
        wait = probe.find("CountDownLatch(1).await()")
        self.assertGreaterEqual(guard, 0, "Probe must validate its effective deadline")
        self.assertGreater(wait, guard, "Validation must precede deliberate blocking")

    def test_probe_has_an_independent_per_test_fallback(self):
        probe = (ROOT / "app/src/test/java/app/gamenative/library/canonical/"
                 "CanonicalHostTimeoutProbeTest.kt").read_text(encoding="utf-8")
        self.assertRegex(probe, r"@Test\(timeout\s*=\s*75_000\)")

    def test_probe_override_runs_after_project_configuration(self):
        initializer = ROOT / "scripts/host_containment_probe.init.gradle"
        self.assertTrue(initializer.exists(), "Reproducible probe initializer is missing")
        script = initializer.read_text(encoding="utf-8")
        self.assertIn("gradle.taskGraph.whenReady", script)
        self.assertIn("test.timeout.set(Duration.ofSeconds(60))", script)
        self.assertIn("gamenative.hostTimeoutProbeDeadlineMs", script)
        self.assertIn("Probe refuses to launch", script)

    def test_progress_includes_completion_not_just_test_start(self):
        self.assertRegex(
            self.configuration,
            r'events\("started",\s*"passed",\s*"skipped",\s*"failed"\)',
        )


if __name__ == "__main__":
    unittest.main()
