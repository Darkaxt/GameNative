"""Run local Windows builds through the installed gate with checkout-isolated E: output."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid


TOOLS = Path(__file__).resolve().parent
DEFAULT_GATE = Path.home() / '.claude/skills/gradle-build-gate/scripts/invoke_gradle_build_gate.py'


def build_plan(project, storage_root):
    project = Path(project).resolve()
    storage_root = Path(storage_root).resolve()
    if storage_root.is_relative_to(project):
        raise ValueError('Build storage must be outside the source checkout')
    if not (project / 'gradlew.bat').is_file():
        raise ValueError('Project must contain gradlew.bat')
    key = hashlib.sha256(os.path.normcase(str(project)).encode('utf-8')).hexdigest()[:16]
    root = storage_root / ('checkout-' + key)
    return {
        'project': str(project),
        'root': str(root),
        'build_root': str(root / 'build'),
        'build_access': str(root / 'build' if project.drive.lower() == storage_root.drive.lower() else project / '.gamenative-local-build'),
        'project_cache': str(root / 'project-cache'),
        'kotlin': str(root / 'kotlin'),
        'tmp': str(root / 'tmp'),
        'logs': str(root / 'logs'),
    }


def ensure_build_access(plan):
    # AGP 8.8 requires test build paths on the source volume. Only this parent is
    # a junction; each module's explicit buildDirectory is an ordinary child, so
    # Gradle clean cannot remove the mount and recreate heavy output on C:.
    mount = Path(plan['build_access'])
    target = Path(plan['build_root'])
    if mount == target:
        return
    if os.path.lexists(mount):
        if not mount.is_junction() or mount.resolve() != target.resolve():
            raise ValueError('Refusing to replace existing build access path: ' + str(mount))
        return
    subprocess.run(['cmd', '/d', '/c', 'mklink', '/J', str(mount), str(target)], check=True)
    if not mount.is_junction() or mount.resolve() != target.resolve():
        raise ValueError('Could not verify external build access junction')


def gate_command(plan, helper, owner_task, gradle_args, thread_name=None):
    command = [sys.executable, '-B', str(helper), 'run', '--project', plan['project'],
               '--owner-task', owner_task, '--log', str(Path(plan['logs']) / ('gradle-' + uuid.uuid4().hex + '.log'))]
    if thread_name:
        command += ['--thread-name', thread_name]
    return command + [
        '--', '--project-cache-dir', plan['project_cache'],
        '--init-script', str(TOOLS / 'local-build.init.gradle'),
        '-Pkotlin.project.persistent.dir=' + plan['kotlin'],
        '-Pgamenative.localBuildRoot=' + plan['build_root'],
        '-Pgamenative.localBuildAccess=' + plan['build_access'],
    ] + list(gradle_args)


def build_environment(plan, inherited):
    environment = dict(inherited)
    for name in ['TEMP', 'TMP', 'TMPDIR']:
        environment[name] = plan['tmp']
    java_tmp = Path(plan['tmp']).as_posix()
    environment['JAVA_TOOL_OPTIONS'] = (environment.get('JAVA_TOOL_OPTIONS', '') +
        f' -Djava.io.tmpdir="{java_tmp}" -XX:HeapDumpPath="{java_tmp}"').strip()
    environment['GAMENATIVE_BUILD_ROOT'] = plan['build_root']
    return environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, default=TOOLS.parent)
    parser.add_argument('--storage-root', type=Path, default=Path('E:/Temp/GameNative'))
    parser.add_argument('--gate-helper', type=Path, default=DEFAULT_GATE)
    parser.add_argument('--owner-task', default='GameNative local build')
    parser.add_argument('--thread-name', help='Actual conversation title, if known')
    parser.add_argument('--plan', action='store_true', help='Print resolved paths without creating files or running Gradle')
    parser.add_argument('gradle_args', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        plan = build_plan(args.project, args.storage_root)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(plan, indent=2), flush=True)
    if args.plan:
        return 0
    if os.name != 'nt':
        parser.error('The installed Windows Gradle gate requires Windows')
    if not args.gate_helper.is_file():
        parser.error('Install the gradle-build-gate skill or supply --gate-helper')
    gradle_args = args.gradle_args
    if gradle_args[:1] == ['--']:
        gradle_args = gradle_args[1:]
    if not gradle_args:
        parser.error('Supply Gradle tasks after --')
    # These are owned by this adapter; conflicting arguments would invalidate its path report.
    for argument in gradle_args:
        if argument.startswith(('-g', '-p', '-c')) or argument in ('--gradle-user-home', '--project-dir', '--settings-file') or argument.startswith((
            '--project-cache-dir', '--gradle-user-home=', '--project-dir=', '--settings-file=',
            '-Pkotlin.project.persistent.dir', '-Pgamenative.localBuildRoot', '-Pgamenative.localBuildAccess',
        )):
            parser.error('Conflicting storage/project argument: ' + argument)
    for name in ['build_root', 'project_cache', 'kotlin', 'tmp', 'logs']:
        Path(plan[name]).mkdir(parents=True, exist_ok=True)
    try:
        ensure_build_access(plan)
    except ValueError as error:
        parser.error(str(error))
    command = gate_command(plan, args.gate_helper, args.owner_task, gradle_args, args.thread_name)
    return subprocess.call(command, env=build_environment(plan, os.environ))


if __name__ == '__main__':
    sys.exit(main())
