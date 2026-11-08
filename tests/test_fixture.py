"""Smoke tests for the packaged Hopliter launcher and Java agent."""

from pathlib import Path
import subprocess
import unittest
from zipfile import ZipFile


JAR = Path(__file__).resolve().parents[1] / "Hopliter-Injector.jar"


def run_jar(*args):
    return subprocess.run(
        ["java", "-Djava.awt.headless=true", "-jar", str(JAR), *args],
        capture_output=True,
        text=True,
        timeout=10,
    )


class InjectorTests(unittest.TestCase):
    def test_manifest_entry_points_are_packaged(self):
        with ZipFile(JAR) as jar:
            manifest = dict(
                line.split(": ", 1)
                for line in jar.read("META-INF/MANIFEST.MF").decode().splitlines()
                if line
            )
            self.assertEqual(manifest["Main-Class"], "com.example.helditemfixture.FixtureMain")
            self.assertEqual(manifest["Agent-Class"], "com.example.helditemfixture.FixtureAgent")
            for key in ("Main-Class", "Agent-Class"):
                self.assertIn(manifest[key].replace(".", "/") + ".class", jar.namelist())

    def test_help_opens_through_the_jar_entry_point(self):
        result = run_jar("--help")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Minecraft Java 1.21.11", result.stdout)
        self.assertIn("--attach <pid> [auto|0-35]", result.stdout)

    def test_invalid_pid_and_slot_are_rejected_before_attach(self):
        cases = [
            (("--attach", "not-a-pid"), "numeric Minecraft process ID"),
            (("--attach", "1", "36"), "slot from 0 to 35"),
        ]
        for args, message in cases:
            with self.subTest(args=args):
                result = run_jar(*args)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn(message, result.stderr)


if __name__ == "__main__":
    unittest.main()
