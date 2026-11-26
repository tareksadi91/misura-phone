#!/usr/bin/env python3
"""Offline source/index checks. Never open local configuration or credential files."""
from pathlib import Path
import hashlib
import re
import subprocess
import unicodedata
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "misura-app/app"
ANDROID = "{http://schemas.android.com/apk/res/android}"
MAIN_EXPORTED = {
    ("activity", ".HomeActivity"): None,
    ("service", ".notify.MisuraListener"): "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    ("receiver", ".work.BootReceiver"): None,
    ("receiver", ".routine.RoutineReceiver"): None,
    ("provider", "rikka.shizuku.ShizukuProvider"): "android.permission.INTERACT_ACROSS_USERS_FULL",
}
DEBUG_EXPORTED = {
    ("receiver", ".debug.DebugReceiver"): "android.permission.DUMP",
    ("activity", ".debug.ProbeActivity"): "android.permission.DUMP",
    ("activity", ".debug.ExperimentActivity"): "android.permission.DUMP",
    ("activity", ".debug.HiddenScreenProbeActivity"): "android.permission.DUMP",
}

# SHA-256 of NFKC/casefolded ASCII words joined by one space. Known values only;
# this cannot discover arbitrary names or certify private Git history.
KNOWN_PRIVATE_IDENTIFIER_DIGESTS = frozenset({
    "c7671f0cf3c584e0f6945dd7a364f2417e37629db0a48e49b25fc2a089c642e8",
    "705e0dbe451533dbc6f06b9112ed5132b841babb5d712ac50ff10d54f741387f",
    "997b365425a2d3c8809dad4d73c5df8fbbaf1c44dfb176b2e20e7a2a8a34553c",
    "127278762a70fea92147a0c32c8baec2bd8f0cf8b6597613ba5c17c6263e886f",
    "76937598f6b59b3c758c8c15c911de811314f19182c77f7020019fe9455d64d4",
    "f2aa04d83e651fd437e142dfe0b9306dae27ccf30a7cbcce3bb42716c948baf9",
})
IDENTIFIER_WORD_LIMIT = 8

# Reviewed complete source lines, including DEBUG guards for content-bearing logs.
# New/changed logging needs review; this is a finite sink check, not Kotlin dataflow analysis.
REVIEWED_LOG_LINES = {
    # A lost gesture answer: a fixed sentence and a duration, no screen words (26 Nov).
    "misura-app/app/src/main/kotlin/com/misura/phone/hidden/HiddenInput.kt": {
        "2498a05851b5b30cd4c56717d2bc217adad8a105a459b4d61b78010efb9ddef1",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/store/RoomStores.kt": {
        "abb09f9daa601340cb40598df4c2faecef585d89a118cf84c0671ec55e894d30",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/work/WorkingService.kt": {
        "517bcd3eec67957406ad0bc241429c0bba35a6ff94a0763eb71926794f7f48c1",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/hidden/ShellService.kt": {
        "bd2d73e396dc3c0146e25803bf01108eb4dd6021e58efdf1555de67995b1b500",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/MisuraGraph.kt": {
        "9a5b99f7d02f62dc22e1eacc3df189749351ad24b5ae06c304d1b0353cef9f0b",
        "7bea531a0489a8ccb5771828549c7cf9b88cc3709db90e564895639f5e4881d6",
        "949c463055c856666e82b68ff90e080924a94436bf4157d4b6b1a903dcbbd3a4",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/hands/Calendar.kt": {
        "1c27031ae7ec09699cc0ec176000564e0c350dbb4f9b6a72d0b5e61438873344",
        "71f7434c4cac7d8bba8ce4d224c6c081f59b5277fc58ce0584abfe5fbc5b58ac",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/hands/Contacts.kt": {
        "7fcfa4751f7483581991444826b24badc7037cefb4350276a4ce8ca6cb45d2db",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/notify/SessionAlerts.kt": {
        "1e745bf48a59236fcdc53094b51b405e784db0f4b6219b5629a360c2e356d66b",
        "36f9ba97962068667fb0be1a625f3872bfda6effc53549a24cc5e532e95159c7",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/routine/Routines.kt": {
        "20fcfd963c308a61a8e8210850074b123cec08343a13ea0bdfaf1346dff6bc62",
        "48fa694917adb64cbc6e3774d345689c75cade4afb3bb9f2dc8907ccbcd36fce",
        "730a377150642a4aa894a3da4e04d115d3f8ecc2c59a912adc3ca61e0c66970e",
        "fc899f913b4592168658e59b677717615bfd5956fc5b7933b04e35afe0d20544",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/notify/ProblemAlert.kt": {
        "2524cdef3335e6e863688331459cf601aff788677ed40d82ab0fa94c5b1cf2e8",
        "e6438a6f0c9939f84614e1216d189b68574710a6793a7305a55c0cd7d4221c5a",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/screen/SheetKeyboard.kt": {
        "c9dcfd81f7f47b37df3ef147bfd1705b4ab8418b30a4445cfb7d8f5bd2a6922e",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/sleep/SleepActivity.kt": {
        "f0fb5626bb2259c634d2cb21b0ba15339b9604c7b0736a7c102638a2ffd789e7",
        "c9709f199fa22c081e101651fbb6ece4786dc0b54ce3c61f88740cc577cdcad8",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/sleep/SleepOverlay.kt": {
        "13829aac6670b1183381aaf0a8ff45db8958eb6678d7802ec273850b90f6b904",
        "18c0f4205b8240393014c563e44d50f5871b306fe3c4cebf1d4f0bd8bc97ad84",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/sleep/WakeWatcher.kt": {
        "3513c64aab74075589a79ec7b35ed53c71bd135276e02fa20eb9bd434fe3c29d",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/store/StorageCipher.kt": {
        "98a60c337412298ab1aa61716bffc2b8246e5b774132b95428eb621bae49a226",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/voice/Voice.kt": {
        "61acef3d8c0165ec4e4a1d3dfec628093b2c3a1fe12522d651bd03e56d4e14e7",
        "7862f3eeb8682644561349c79baa44bac27c9b42f721491b79b037ac8cd25003",
        "81a5a7c79b5bf7ec4887786b5f193ababd893e8df6bd9a2fd3efe315e3c3f1b5",
    },
    "misura-app/app/src/main/kotlin/com/misura/phone/work/BootReceiver.kt": {
        "760d8d525e25da9a68e8aa5b6f3ab587b6c6b2f45a619998ada30f452f58781f",
    },
}
LOG_SINK = re.compile(
    r"\bLog\s*\.|\bTimber\s*\.|\b(?:print|println|printStackTrace)\s*\(|"
    r"\bSystem\s*\.\s*(?:out|err)\b|"
    r"^\s*import\s+(?:android\.util\.Log(?:\.|\s+as\b)|java\.util\.logging\.)",
    re.MULTILINE,
)


def check_exported_components(manifest, expected, exact=False):
    application = manifest.find("application")
    assert application is not None, "missing manifest application"
    components = [c for c in application if c.tag in {"activity", "activity-alias", "service", "receiver", "provider"}]
    identities = [(c.tag, c.get(ANDROID + "name")) for c in components]
    assert len(identities) == len(set(identities)), "duplicate manifest component"
    exported = {}
    for component in components:
        flag = component.get(ANDROID + "exported")
        assert flag in {"true", "false"}, "component must explicitly declare exported"
        if flag == "true":
            identity = (component.tag, component.get(ANDROID + "name"))
            if component.tag == "provider":
                assert all(component.get(ANDROID + attr) is None for attr in ("readPermission", "writePermission")), "provider permission override"
                assert component.find("path-permission") is None, "provider permission override"
            exported[identity] = component.get(ANDROID + "permission", application.get(ANDROID + "permission"))
    assert exported == expected, "exported component/permission allowlist changed"
    assert not exact or len(components) == len(expected), "unexpected debug component"


def check_main_logging(path, source):
    for match in LOG_SINK.finditer(source):
        start = source.rfind("\n", 0, match.start()) + 1
        end = source.find("\n", match.end())
        line = source[start:end if end >= 0 else len(source)].strip()
        digest = hashlib.sha256(line.encode()).hexdigest()
        assert digest in REVIEWED_LOG_LINES.get(path.as_posix(), set()), f"unreviewed main logging: {path}"


def check_private_identifiers(path, source, digests=KNOWN_PRIVATE_IDENTIFIER_DIGESTS):
    assert all(re.fullmatch(r"[0-9a-f]{64}", digest) for digest in digests), "invalid private identifier digest"
    words = re.findall(r"[a-z0-9]+", unicodedata.normalize("NFKC", source).casefold())
    for start in range(len(words)):
        for size in range(1, min(IDENTIFIER_WORD_LIMIT, len(words) - start) + 1):
            digest = hashlib.sha256(" ".join(words[start:start + size]).encode()).hexdigest()
            assert digest not in digests, f"known private-identifier candidate (value withheld): {path}"


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def restricted(path):
    return any(".local." in part for part in path.parts) or path.name in {
        "local.properties", "keystore.properties", "sa.json", ".env",
    } or path.name.startswith((".env.", "credentials", "service-account")) or path.suffix.lower() in {
        ".key", ".pem", ".p12", ".pfx", ".jks", ".keystore",
    }


def read(path):
    assert not restricted(path) and not path.is_symlink(), f"refusing restricted path: {path.name}"
    return path.read_text()


def main():
    tracked = [Path(p.decode()) for p in git("ls-files", "-z", "--", ".").split(b"\0") if p]
    for path in tracked:
        assert not restricted(path), f"private file tracked: {path}"
        assert not {"build", ".gradle", ".kotlin"}.intersection(path.parts), f"output tracked: {path}"
        assert path.suffix not in {".apk", ".aab", ".apks"}, f"package tracked: {path}"
        assert (ROOT / path).stat().st_size <= 1024 * 1024, f"large tracked file needs review: {path}"

    # Synthetic paths test the policy without opening or creating any credential file.
    for name in ("sa.local.json", "about.local.md", "sample.local.txt", "nested/input.local.yaml",
                 "key.pem", "release.keystore", ".env", ".env.dev", "build/report.html",
                 "misura-app/app/build/outputs/apk/release/app-release.apk", "misura-app/local.properties"):
        assert subprocess.run(["git", "-C", str(ROOT), "check-ignore", "-q", "--", name]).returncode == 0, f"not ignored: {name}"

    main = ET.fromstring(read(APP / "src/main/AndroidManifest.xml"))
    debug = ET.fromstring(read(APP / "src/debug/AndroidManifest.xml"))
    app = main.find("application")
    for attr in ("allowBackup", "fullBackupContent", "usesCleartextTraffic"):
        assert app.get(ANDROID + attr) == "false", f"main manifest must deny {attr}"
    assert app.get(ANDROID + "dataExtractionRules") == "@xml/data_extraction_rules"
    assert all(".debug." not in c.get(ANDROID + "name", "") for c in app)
    assert "android.permission.WRITE_CONTACTS" not in {p.get(ANDROID + "name") for p in main.findall("uses-permission")}
    check_exported_components(main, MAIN_EXPORTED)
    check_exported_components(debug, DEBUG_EXPORTED, exact=True)
    rules = ET.fromstring(read(APP / "src/main/res/xml/data_extraction_rules.xml"))
    domains = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"}
    for mode in ("cloud-backup", "device-transfer"):
        assert {e.get("domain") for e in rules.find(mode).findall("exclude") if e.get("path") == "."} == domains

    src = APP / "src/main/kotlin/com/misura/phone"
    listener = read(src / "notify/MisuraListener.kt")
    assert listener.index("!access.allows(sbn.packageName)") < listener.index("extract(sbn)")
    assert "it.packageName != packageName && access.allows(it.packageName)" in listener
    assert "if (pkg == packageName || !access.allows(pkg) || !AppAccess.allows(pkg)) emptyList()" in listener
    assert listener.index("!AppAccess.allows(sbn.packageName)") < listener.index("extract(sbn)")
    access = read(src / "notify/NotificationAccess.kt")
    assert "!prefs.getBoolean(pkg, false)" in access
    assert 'getSharedPreferences("notification_blocks"' in access and "fun setBlocked(" in access
    cipher = read(src / "store/StorageCipher.kt")
    for required in ("AES/GCM/NoPadding", "AndroidKeyStore", "GCMParameterSpec(128", "misura:enc:v1:", "setKeySize(256)"):
        assert required in cipher
    assert "cipher.decrypt(stored) ?: return@synchronized null" in cipher
    assert "fun SharedPreferences.privateString(" in cipher
    assert "MisuraGraph.inbox.prune()" in read(src / "MisuraApp.kt")
    debug_src = APP / "src/debug/kotlin/com/misura/phone/debug"
    receiver = read(debug_src / "DebugReceiver.kt")
    assert receiver.index("if (!com.misura.phone.BuildConfig.DIAGNOSTICS) return") < receiver.index("goAsync()")
    for name in ("DebugReceiver", "ProbeActivity", "ExperimentActivity", "HiddenScreenProbeActivity", "SpikeReport"):
        assert (debug_src / f"{name}.kt").is_file(), f"missing debug-only source: {name}"
        assert not (src / "debug" / f"{name}.kt").exists(), f"debug implementation in main: {name}"
    probe = read(src / "notify/ProbeLog.kt")
    assert "BuildConfig.DIAGNOSTICS" in probe and "BuildConfig.DEBUG" not in probe and "if (!debug) return" in probe
    assert "MAX_BYTES" in probe and "MAX_AGE_MS" in probe
    for name in ("msgs", "extras", "conv", "sender", "text"):
        assert f'put("{name}"' not in probe, f"probe contains content field: {name}"
    assert "legacy.delete()" in probe
    assert "ProbeLog.clean(this)" in read(src / "MisuraApp.kt")
    extract = read(src / "notify/Extract.kt")
    assert "val raw = if (com.misura.phone.BuildConfig.DEBUG)" in extract
    assert read(src / "brain/Vertex.kt").count("conn.instanceFollowRedirects = headers.isEmpty()") == 2
    assert "conn.instanceFollowRedirects = false" in read(src / "brain/ServiceAccountToken.kt")
    assert "if (n.isPassword) return UiNode(null, null, null" in read(src / "hidden/ScreenReader.kt")
    assert "typeWindowContentChanged" not in read(APP / "src/main/res/xml/misura_accessibility.xml")

    # Values are never printed. This is a narrow pattern scan, not proof of secret absence.
    secret = re.compile(
        r"AIza[0-9A-Za-z_-]{30,}|ya29\.[0-9A-Za-z._-]{20,}|"
        r"(?:gh[pousr]_|github_pat_|sk-|xox[baprs]-)[0-9A-Za-z_-]{20,}|"
        r"eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}|"
        r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/=]{40,}"
    )
    paths = {Path(p.decode()) for p in git("ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", ".").split(b"\0") if p}
    scanned = 0
    for path in sorted(paths):
        if restricted(path) or (ROOT / path).is_symlink() or path.match("Zz*Shots.kt"):
            continue
        if path.suffix not in {".kt", ".kts", ".md", ".sh", ".py", ".html", ".xml", ".json", ".toml", ".properties"}:
            continue
        source = read(ROOT / path)
        if path.suffix == ".kt" and "/src/main/" in path.as_posix():
            check_main_logging(path, source)
        assert not secret.search(source), f"credential-pattern candidate (value withheld): {path}"
        # Catch concrete workstation paths / device serials without embedding old identifiers.
        personal = re.compile(r"/Users/[A-Za-z0-9._-]+/|(?i:serial[=: ]+)[A-Z0-9]{12,}\b")
        assert not personal.search(source), f"private-identifier candidate: {path}"
        check_private_identifiers(path, source)
        scanned += 1

    for name in ("LICENSE", "NOTICE", "SECURITY.md", "THIRD_PARTY_NOTICES.md", "licenses/Hanken-Grotesk-OFL.txt", "docs/OSS_AUDIT.md"):
        assert (ROOT / name).is_file(), f"missing publication file: {name}"
    print(f"PASS: {len(tracked)} tracked paths; {scanned} text files scanned; ignore, manifest and source security checks.")
    print("Not checked: credential files, historical blobs, arbitrary names/secrets, Kotlin dataflow, merged manifests, Gradle compilation, device behavior, dependency resolution or live network traffic.")


if __name__ == "__main__":
    main()
