#!/usr/bin/env python3
"""Mutation regressions using tracked public sources and isolated synthetic fixtures."""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name("check-oss.py")
spec = importlib.util.spec_from_file_location("check_oss", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
RUNNER = """
import importlib.util, pathlib, sys, xml.etree.ElementTree as ET
spec = importlib.util.spec_from_file_location('check_oss', sys.argv[1])
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
source = checker.read(pathlib.Path(sys.argv[3]))
mode = sys.argv[2]
if mode in ('main', 'debug'):
    checker.check_exported_components(ET.fromstring(source), checker.MAIN_EXPORTED if mode == 'main' else checker.DEBUG_EXPORTED, exact=mode == 'debug')
elif mode == 'logging':
    checker.check_main_logging(pathlib.Path(sys.argv[4]), source)
else:
    checker.check_private_identifiers(pathlib.Path('fixture.txt'), source, {sys.argv[4]})
"""


class SecurityMutations(unittest.TestCase):
    def tracked_source(self, relative):
        subprocess.run(["git", "-C", str(checker.ROOT), "ls-files", "--error-unmatch", "--", relative],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        return checker.read(checker.ROOT / relative)

    def execute(self, mode, source, extra="", error=None, withheld=()):
        with tempfile.TemporaryDirectory(prefix="oss-check-") as directory:
            fixture = Path(directory) / "fixture.txt"
            fixture.write_text(source)
            result = subprocess.run([sys.executable, "-B", "-c", RUNNER, str(SCRIPT), mode, str(fixture), extra],
                                    capture_output=True, text=True)
        output = result.stdout + result.stderr
        for value in withheld:
            self.assertNotIn(value, output)
        if error is None:
            self.assertEqual(result.returncode, 0, output)
        else:
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(error, output)

    def test_exported_component_and_permission_mutations(self):
        for mode in ("main", "debug"):
            source = self.tracked_source(f"misura-app/app/src/{mode}/AndroidManifest.xml")
            self.execute(mode, source)
            manifest = ET.fromstring(source)
            app = manifest.find("application")
            ET.SubElement(app, "receiver", {checker.ANDROID + "name": ".FixtureReceiver", checker.ANDROID + "exported": "true"})
            self.execute(mode, ET.tostring(manifest, encoding="unicode"), error="allowlist changed")
            manifest = ET.fromstring(source)
            protected = next(c for c in manifest.find("application") if c.get(checker.ANDROID + "permission"))
            del protected.attrib[checker.ANDROID + "permission"]
            self.execute(mode, ET.tostring(manifest, encoding="unicode"), error="allowlist changed")

    def test_provider_permission_overrides_rejected(self):
        source = self.tracked_source("misura-app/app/src/main/AndroidManifest.xml")
        for attr in ("readPermission", "writePermission", "path-permission"):
            manifest = ET.fromstring(source)
            provider = manifest.find("application/provider")
            if attr == "path-permission":
                ET.SubElement(provider, attr, {checker.ANDROID + "pathPrefix": "/", checker.ANDROID + "permission": "fixture.permission"})
            else:
                provider.set(checker.ANDROID + attr, "fixture.permission")
            self.execute("main", ET.tostring(manifest, encoding="unicode"), error="provider permission override")

    def test_debug_rejects_extra_nonexported_component(self):
        source = self.tracked_source("misura-app/app/src/debug/AndroidManifest.xml")
        manifest = ET.fromstring(source)
        ET.SubElement(manifest.find("application"), "activity", {
            checker.ANDROID + "name": ".FixtureActivity", checker.ANDROID + "exported": "false"})
        self.execute("debug", ET.tostring(manifest, encoding="unicode"), error="unexpected debug component")

    def test_logging_outside_previously_checked_files(self):
        path = "misura-app/app/src/main/kotlin/com/misura/phone/voice/Voice.kt"
        source = self.tracked_source(path)
        self.execute("logging", source, path)
        self.execute("logging", source + '\nfun fixture(content: String) { android.util.Log.i("Fixture", content) }\n', path,
                     error="unreviewed main logging")
        for sink in ('Log.i("Fixture", content)', 'println(content)', 'error.printStackTrace()',
                     'android.util.Log\n    .i("Fixture", content)',
                     'import android.util.Log as DiagnosticLog\nDiagnosticLog.i("Fixture", content)'):
            self.execute("logging", sink, "misura-app/core/src/main/kotlin/Fixture.kt", error="unreviewed main logging")

    def test_content_guard_removal_and_same_line_bypass(self):
        path = "misura-app/app/src/main/kotlin/com/misura/phone/MisuraGraph.kt"
        source = self.tracked_source(path)
        self.execute("logging", source, path)
        self.assertIn("if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS)", source)
        for mutation in (source.replace("if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS)", "if (true)"),
                         source.replace('android.util.Log.i("MisuraAgent", it)',
                                        'android.util.Log.i("MisuraAgent", it); android.util.Log.i("Fixture", it)')):
            self.execute("logging", mutation, path, error="unreviewed main logging")

    def test_known_identifier_digest_rejects_without_disclosure(self):
        identifier = "Synthetic Fixture Identity"
        digest = hashlib.sha256(identifier.casefold().encode()).hexdigest()
        self.execute("identifier", "Public role fixture", digest)
        for mutation in (identifier, identifier.upper().replace(" ", "-"), "Ｓｙｎｔｈｅｔｉｃ Fixture Identity"):
            self.execute("identifier", "prefix '" + mutation + "' suffix", digest,
                         error="known private-identifier candidate (value withheld)", withheld=(identifier, mutation))


if __name__ == "__main__":
    unittest.main()
