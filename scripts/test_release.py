"""Focused tests for the distribution signing contract."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest import mock


MODULE_PATH = Path(__file__).with_name("release.py")
SPEC = importlib.util.spec_from_file_location("release", MODULE_PATH)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


APPROVED = release.POLICY["certificate_sha256"]


class ReleaseSigningContractTests(unittest.TestCase):
    def test_inspect_rejects_corrupt_zip_payload(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "corrupt.apk"
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_STORED) as archive:
                archive.writestr("classes.dex", b"payload")
            data = bytearray(apk.read_bytes())
            data[30 + len("classes.dex")] ^= 1
            apk.write_bytes(data)
            badging = "package: name='space.deytt.connect' versionCode='85' versionName='0.11.55'\nsdkVersion:'24'\ntargetSdkVersion:'35'"
            with mock.patch.object(release, "tool", return_value="aapt"), \
                    mock.patch.object(release, "run", return_value=badging):
                with self.assertRaisesRegex(ValueError, "CRC check"):
                    release.inspect_apk(apk)

    def test_inspect_rejects_duplicate_zip_entries(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "duplicate.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"first")
                with self.assertWarns(UserWarning):
                    archive.writestr("classes.dex", b"second")
            badging = "package: name='space.deytt.connect' versionCode='85' versionName='0.11.55'\nsdkVersion:'24'\ntargetSdkVersion:'35'"
            with mock.patch.object(release, "tool", return_value="aapt"), \
                    mock.patch.object(release, "run", return_value=badging):
                with self.assertRaisesRegex(ValueError, "duplicate ZIP"):
                    release.inspect_apk(apk)

    def test_keytool_checks_fixed_alias_and_uses_password_file_reference(self):
        with mock.patch.object(release, "keytool", return_value="keytool"), \
                mock.patch.object(release, "run", return_value="\r\n  SHA256: " + ":".join(
                    APPROVED[index:index + 2] for index in range(0, 64, 2)
                )) as run:
            release.check_keystore_certificate(Path("release.p12"), Path("password.txt"))

        args = run.call_args.args
        self.assertIn("-storepass:file", args)
        self.assertIn("password.txt", args)
        self.assertNotIn("deytt-connect-password", args)
        self.assertEqual(args[-2:], ("-alias", "deytt-connect"))

    def test_wrong_keystore_certificate_is_rejected(self):
        wrong = "00" * 32
        with mock.patch.object(release, "keytool", return_value="keytool"), \
                mock.patch.object(release, "run", return_value="SHA256: " + wrong):
            with self.assertRaisesRegex(ValueError, "does not match the approved release signer"):
                release.check_keystore_certificate(Path("release.p12"), Path("password.txt"))

    def test_sign_stops_before_apksigner_when_keystore_certificate_is_wrong(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            keystore = root / "other.p12"
            password_file = root / "password.txt"
            keystore.write_bytes(b"fixture")
            password_file.write_text("fixture")
            args = type("Args", (), {
                "apk": root / "unsigned.apk", "qa": False, "expected_version": None,
                "expected_version_code": None, "output": root / "release.apk",
                "keystore": keystore, "password_file": password_file,
            })()
            with mock.patch.object(release, "inspect_apk"), \
                    mock.patch.object(release, "check_keystore_certificate",
                                      side_effect=ValueError("selected keystore certificate does not match the approved release signer")), \
                    mock.patch.object(release, "run") as run:
                with self.assertRaisesRegex(ValueError, "does not match the approved release signer"):
                    release.sign(args)
            run.assert_not_called()

    def test_verify_requires_same_fixed_signer_on_supported_api_ranges(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "release.apk"
            apk.write_bytes(b"signed apk fixture")
            signer_output = f"Signer #1 certificate SHA-256 digest: {APPROVED}"
            with mock.patch.object(release, "inspect_apk", return_value="package identity"), \
                    mock.patch.object(release, "tool", return_value="apksigner"), \
                    mock.patch.object(release, "run", return_value=signer_output) as run:
                digest = release.verify(apk)

        self.assertEqual(len(digest), 64)
        calls = [call.args for call in run.call_args_list]
        ranges = []
        for call in calls:
            minimum = call[call.index("--min-sdk-version") + 1]
            maximum = call[call.index("--max-sdk-version") + 1] if "--max-sdk-version" in call else None
            ranges.append((minimum, maximum))
        self.assertEqual(ranges, [("24", "27"), ("28", None), ("36", "36")])

    def test_verify_rejects_wrong_certificate(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "release.apk"
            apk.write_bytes(b"signed apk fixture")
            wrong_output = "Signer #1 certificate SHA-256 digest: " + "00" * 32
            with mock.patch.object(release, "inspect_apk", return_value="package identity"), \
                    mock.patch.object(release, "tool", return_value="apksigner"), \
                    mock.patch.object(release, "run", return_value=wrong_output):
                with self.assertRaisesRegex(ValueError, "Unexpected signing certificate for API 24"):
                    release.verify(apk)

    def test_legacy_signing_and_fingerprint_override_flags_are_rejected(self):
        parser = release.build_parser()
        for flag in (
            "--legacy-keystore", "--legacy-key-alias", "--legacy-password-env",
            "--lineage-file", "--legacy-certificate-sha256", "--release-certificate-sha256",
        ):
            stderr = io.StringIO()
            with self.subTest(flag=flag), contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as raised:
                parser.parse_args([
                    "sign", "unsigned.apk", "--output", "release.apk",
                    "--keystore", "release.p12", "--password-file", "password.txt",
                    flag, "value",
                ])
            self.assertEqual(raised.exception.code, 2)
            self.assertIn("unrecognized arguments", stderr.getvalue())
            self.assertIn(flag, stderr.getvalue())

    def test_sign_checks_certificate_before_single_key_signing_and_promotes_verified_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "unsigned.apk"
            output = root / "release.apk"
            keystore = root / "approved-backup.p12"
            password_file = root / "release.password"
            apk.write_bytes(b"unsigned fixture")
            keystore.write_bytes(b"opaque test fixture")
            password_file.write_text("opaque test fixture")
            args = type("Args", (), {
                "apk": apk, "qa": False, "expected_version": None,
                "expected_version_code": None, "output": output,
                "keystore": keystore, "password_file": password_file,
            })()

            def fake_run(*command):
                if "sign" in command:
                    target = Path(command[command.index("--out") + 1])
                    target.write_bytes(b"signed fixture")
                return ""

            with mock.patch.object(release, "inspect_apk"), \
                    mock.patch.object(release, "check_keystore_certificate") as check, \
                    mock.patch.object(release, "tool", return_value="apksigner"), \
                    mock.patch.object(release, "run", side_effect=fake_run) as run, \
                    mock.patch.object(release, "verify", return_value="a" * 64):
                release.sign(args)
            check.assert_called_once_with(keystore, password_file)
            sign_command = next(call.args for call in run.call_args_list if "sign" in call.args)
            self.assertEqual(sign_command.count("--ks"), 1)
            self.assertIn("--ks-key-alias", sign_command)
            self.assertIn("deytt-connect", sign_command)
            self.assertFalse(any("lineage" in str(value) or "next-signer" in str(value) for value in sign_command))
            self.assertTrue(output.is_file())
            self.assertEqual(output.read_bytes(), b"signed fixture")
            self.assertEqual(output.with_suffix(".apk.sha256").read_text(), f"{'a' * 64}  {output.name}\n")


if __name__ == "__main__":
    unittest.main()
