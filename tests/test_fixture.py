"""Smoke tests for the packaged launcher, Paper plugin and Minecraft bindings."""
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target" / "Hopliter-Multiplayer.jar"


def run_jar(*args):
    return subprocess.run(["java", "-Djava.awt.headless=true", "-jar", str(JAR), *args],
                          capture_output=True, text=True, timeout=10)


class InjectorTests(unittest.TestCase):
    def test_manifest_entry_points(self):
        with ZipFile(JAR) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode()
            self.assertIn("Main-Class: com.example.helditemfixture.FixtureMain", manifest)
            self.assertIn("Agent-Class: com.example.helditemfixture.FixtureAgent", manifest)
            plugin = jar.read("plugin.yml").decode()
            self.assertIn("main: com.example.helditemfixture.HopliterPlugin", plugin)
            self.assertIn("permission: hopliter.copy", plugin)
            self.assertIn("default: op", plugin)
            self.assertIn("com/example/helditemfixture/HopliterPlugin.class", jar.namelist())

    def test_help(self):
        result = run_jar("--help")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("HOTBAR SLOT 1 to HOTBAR SLOT 2", result.stdout)
        self.assertIn("26.3", result.stdout)

    def test_invalid_pid_and_destination(self):
        cases = [
            (("--attach", "not-a-pid"), "numeric Minecraft process ID"),
            (("--attach", "1", "36"), "destination index from 1 to 35"),
            (("--attach", "1", "0"), "different destination"),
        ]
        for args, message in cases:
            with self.subTest(args=args):
                result = run_jar(*args)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn(message, result.stderr)

    def test_java_runtime_copy_and_command_dispatch(self):
        sources = sorted(str(path) for path in (ROOT / "tests" / "java").rglob("*.java"))
        with TemporaryDirectory() as directory:
            classes = Path(directory)
            build = subprocess.run(
                ["javac", "--release", "21", "-cp", str(JAR), "-d", str(classes), *sources],
                capture_output=True, text=True, timeout=15,
            )
            self.assertEqual(build.returncode, 0, build.stderr)
            (classes / "version.json").write_text('{"id":"26.3"}')
            result = subprocess.run(
                ["java", "-cp", os.pathsep.join([str(JAR), str(classes)]),
                 "com.example.helditemfixture.FixtureRunnerTest"],
                capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
