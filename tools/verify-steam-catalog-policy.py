"""Run the real pure-Kotlin catalog tests without configuring the Android build."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/app/gamenative"
TEST = ROOT / "app/src/test/java/app/gamenative/library/canonical/catalog"
TEST_CLASSES = (
    "SteamCatalogCandidatePolicyTest",
    "SteamCatalogNormalizationTest",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-home", type=Path, required=True)
    parser.add_argument("--temp-root", type=Path, required=True)
    args = parser.parse_args()
    lib = args.gradle_home / "lib"
    compiler_jars = sorted(lib.glob("*.jar"))
    if not list(lib.glob("kotlin-compiler-embeddable-*.jar")):
        parser.error("Gradle home must contain its bundled Kotlin compiler")
    if not args.temp_root.is_dir():
        parser.error("Temporary root must be an existing directory")
    dependency_patterns = (
        "kotlin-stdlib-*.jar",
        "javax.inject-*.jar",
        "junit-4*.jar",
        "hamcrest-core-*.jar",
        "annotations-*.jar",
    )
    dependencies = []
    for pattern in dependency_patterns:
        matches = sorted(lib.glob(pattern))
        if len(matches) != 1:
            parser.error(f"Expected exactly one dependency matching {pattern}")
        dependencies.extend(matches)
    sources = [
        MAIN / "data/canonical/CanonicalMatching.kt",
        MAIN / "library/canonical/catalog/SteamCatalogModels.kt",
        MAIN / "library/canonical/catalog/SteamCatalogNormalization.kt",
        MAIN / "library/canonical/catalog/SteamCatalogCandidatePolicy.kt",
        *(TEST / f"{name}.kt" for name in TEST_CLASSES),
    ]
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    compiler_classpath = os.pathsep.join(map(str, compiler_jars))
    test_classpath = os.pathsep.join(map(str, dependencies))
    with tempfile.TemporaryDirectory(prefix="gamenative-policy-", dir=args.temp_root) as output:
        subprocess.run(
            [java, "-cp", compiler_classpath,
             "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
             "-no-stdlib", "-no-reflect", "-jvm-target", "17",
             "-classpath", test_classpath, "-d", output,
             *map(str, sources)],
            check=True,
        )
        result = subprocess.run(
            [java, "-cp", os.pathsep.join((output, test_classpath)),
             "org.junit.runner.JUnitCore",
             *(f"app.gamenative.library.canonical.catalog.{name}" for name in TEST_CLASSES)],
            check=False,
        )
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
