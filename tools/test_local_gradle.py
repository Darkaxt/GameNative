import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class LocalGradleTest(unittest.TestCase):
    def setUp(self):
        source = Path(__file__).with_name('local_gradle.py')
        self.assertTrue(source.is_file(), 'External-output Gradle launcher is not implemented')
        spec = importlib.util.spec_from_file_location('local_gradle', source)
        self.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.tool)
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.base = Path(self.scratch.name)
        self.project = self.base / 'source' / 'GameNative'
        self.project.mkdir(parents=True)
        (self.project / 'gradlew.bat').write_text('@exit /b 0\n')
        self.storage = self.base / 'external'
        self.helper = self.base / 'installed-gate.py'

    def test_same_named_checkouts_have_distinct_stable_external_paths(self):
        first = self.tool.build_plan(self.project, self.storage)
        self.assertEqual(first, self.tool.build_plan(self.project, self.storage))
        other = self.base / 'worktree' / 'GameNative'
        other.mkdir(parents=True)
        (other / 'gradlew.bat').write_text('@exit /b 0\n')
        self.assertNotEqual(first['root'], self.tool.build_plan(other, self.storage)['root'])
        for name in ['build_root', 'project_cache', 'kotlin', 'tmp', 'logs']:
            self.assertTrue(Path(first[name]).is_relative_to(self.storage.resolve()))
        self.assertFalse(self.storage.exists(), 'Planning must not create output or mutate source')

    def test_storage_inside_source_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            self.tool.build_plan(self.project, self.project / 'scratch')
        with self.assertRaisesRegex(ValueError, 'outside'):
            self.tool.build_plan(self.project, self.project)

    def test_existing_source_directory_is_never_replaced_by_build_mount(self):
        plan = self.tool.build_plan(self.project, self.storage)
        mount = self.project / '.gamenative-local-build'
        plan['build_access'] = str(mount)
        mount.mkdir()
        sentinel = mount / 'keep.txt'
        sentinel.write_text('existing source stays')
        self.assertTrue(hasattr(self.tool, 'ensure_build_access'), 'Missing guarded cross-drive alias creation')
        with self.assertRaisesRegex(ValueError, 'existing'):
            self.tool.ensure_build_access(plan)
        self.assertEqual(sentinel.read_text(), 'existing source stays')

    def test_gate_carries_access_root_without_unnecessary_same_drive_junction(self):
        plan = self.tool.build_plan(self.project, self.storage)
        self.assertIn('build_access', plan, 'Missing same-volume access path for Android test configuration')
        self.assertEqual(plan['build_access'], plan['build_root'])
        command = self.tool.gate_command(plan, self.helper, 'Test path alias', ['help'])
        self.assertIn('-Pgamenative.localBuildAccess=' + plan['build_access'], command)

    def test_attached_project_and_shared_cache_overrides_are_rejected_before_writes(self):
        for argument in ['-gC:/other-cache', '-pC:/other-project', '-cC:/other-settings.gradle']:
            with self.subTest(argument=argument):
                result = subprocess.run([
                    sys.executable, '-B', str(Path(__file__).with_name('local_gradle.py')),
                    '--project', str(self.project), '--storage-root', str(self.storage),
                    '--gate-helper', str(Path(__file__).with_name('local_gradle.py')),
                    '--', argument, 'help',
                ], capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertIn('Conflicting storage/project argument', result.stderr)
                self.assertFalse(self.storage.exists())

    def test_cli_plan_does_not_create_output_or_require_installed_gate(self):
        result = subprocess.run([
            sys.executable, '-B', str(Path(__file__).with_name('local_gradle.py')),
            '--project', str(self.project), '--storage-root', str(self.storage),
            '--gate-helper', str(self.helper), '--plan',
        ], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(self.tool.build_plan(self.project, self.storage)['root'].replace('\\', '\\\\'), result.stdout)
        self.assertFalse(self.storage.exists())
        self.assertFalse((self.project / '.gamenative-local-build').exists())

    def test_cli_invokes_gate_once_and_propagates_failure(self):
        receipt = self.base / 'gate-calls.txt'
        self.helper.write_text(
            'from pathlib import Path\n'
            f'p = Path({str(receipt)!r})\n'
            'with p.open("a") as f: f.write("called\\n")\n'
            'raise SystemExit(7)\n'
        )
        result = subprocess.run([
            sys.executable, '-B', str(Path(__file__).with_name('local_gradle.py')),
            '--project', str(self.project), '--storage-root', str(self.storage),
            '--gate-helper', str(self.helper), '--', 'help',
        ], capture_output=True, text=True)
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual(receipt.read_text(), 'called\n')
        plan = self.tool.build_plan(self.project, self.storage)
        self.assertEqual(Path(plan['build_access']).resolve(), Path(plan['build_root']).resolve())

    def test_debug_asset_merges_depend_on_generated_manifest(self):
        source = Path(__file__).resolve().parents[1] / 'app/build.gradle.kts'
        script = source.read_text()
        self.assertIn('it.name.startsWith("merge") && it.name.endsWith("DebugAssets")', script)
        self.assertIn('dependsOn(copyDebugManifest)', script)

    def test_windows_native_scratch_follows_external_app_build(self):
        for name in ['build-xr-native.ps1', 'build-windows-xr-runtime.ps1']:
            with self.subTest(script=name):
                source = Path(__file__).with_name(name).read_text()
                self.assertIn('$env:GAMENATIVE_BUILD_ROOT', source)
                self.assertIn('Join-Path $env:GAMENATIVE_BUILD_ROOT "app"', source)
                self.assertIn('Join-Path $repository "app\\build"', source)

    def test_gate_and_environment_preserve_shared_settings(self):
        plan = self.tool.build_plan(self.project, self.storage)
        command = self.tool.gate_command(plan, self.helper, 'Verify external output', [':app:help'], 'Actual thread')
        self.assertIn(str(self.helper), command)
        self.assertEqual(command[command.index('--project') + 1], str(self.project.resolve()))
        self.assertEqual(command[command.index('--owner-task') + 1], 'Verify external output')
        self.assertEqual(command[command.index('--thread-name') + 1], 'Actual thread')
        self.assertEqual(command[command.index('--project-cache-dir') + 1], plan['project_cache'])
        self.assertIn('-Pkotlin.project.persistent.dir=' + plan['kotlin'], command)
        self.assertIn('-Pgamenative.localBuildRoot=' + plan['build_root'], command)
        self.assertEqual(command[-1], ':app:help')
        inherited = {'GRADLE_USER_HOME': 'shared-gradle', 'ANDROID_HOME': 'shared-sdk', 'JAVA_TOOL_OPTIONS': '-Dexisting=value'}
        environment = self.tool.build_environment(plan, inherited)
        self.assertEqual(inherited, {'GRADLE_USER_HOME': 'shared-gradle', 'ANDROID_HOME': 'shared-sdk', 'JAVA_TOOL_OPTIONS': '-Dexisting=value'})
        for name in ['TEMP', 'TMP', 'TMPDIR']:
            self.assertEqual(environment[name], plan['tmp'])
        self.assertEqual(environment['GRADLE_USER_HOME'], 'shared-gradle')
        self.assertEqual(environment['ANDROID_HOME'], 'shared-sdk')
        self.assertIn('-Dexisting=value', environment['JAVA_TOOL_OPTIONS'])
        self.assertIn('-Djava.io.tmpdir=', environment['JAVA_TOOL_OPTIONS'])
        self.assertEqual(environment['GAMENATIVE_BUILD_ROOT'], plan['build_root'])


if __name__ == '__main__':
    unittest.main()
