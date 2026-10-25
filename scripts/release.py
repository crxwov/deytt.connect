#!/usr/bin/env python3
"""sign and verify distribution apks; private keys always stay outside the repo."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
POLICY = json.loads((ROOT / "release/signing-policy.json").read_text())
RELEASE_KEY_ALIAS = "deytt-connect"


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME", ROOT / ".toolchain/android-sdk"))
    path = sdk / "build-tools/35.0.0" / name
    if os.name == "nt":
        path = path.with_suffix(".bat" if name == "apksigner" else ".exe")
    if not path.is_file():
        raise ValueError(f"install android build-tools 35.0.0: missing {name}")
    return str(path)


def keytool():
    java_home = os.environ.get("JAVA_HOME")
    candidate = Path(java_home) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool") if java_home else None
    found = candidate if candidate and candidate.is_file() else shutil.which("keytool")
    if not found:
        raise ValueError("JDK keytool is required to check the signing certificate")
    return str(found)


def normalize_fingerprint(value):
    fingerprint = value.lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise ValueError("invalid signing certificate fingerprint")
    return fingerprint


def check_keystore_certificate(keystore, password_file):
    """Check the selected PKCS#12 certificate without reading its password in Python."""
    output = run(
        keytool(), "-J-Duser.language=en", "-J-Duser.country=US", "-list", "-v",
        "-keystore", str(keystore), "-storepass:file", str(password_file),
        "-alias", RELEASE_KEY_ALIAS,
    )
    fingerprints = re.findall(r"^[ \t]*SHA256:[ \t]*([0-9a-fA-F:]+)$", output, re.M)
    if (len(fingerprints) != 1 or
            normalize_fingerprint(fingerprints[0]) != normalize_fingerprint(POLICY["certificate_sha256"])):
        raise ValueError("selected keystore certificate does not match the approved release signer")


def inspect_apk(apk, qa=False, expected_version=None, expected_version_code=None):
    badging = run(tool("aapt"), "dump", "badging", str(apk))
    package = POLICY["package"] + (".qa" if qa else "")
    identities = badging.splitlines()
    if not identities:
        raise ValueError("aapt returned no package identity")
    identity = identities[0]
    if not identity.startswith(f"package: name='{package}' "):
        raise ValueError("unexpected package identity")
    if expected_version and f"versionName='{expected_version}'" not in identity:
        raise ValueError("apk version name does not match the release tag")
    if expected_version_code is not None and f"versionCode='{expected_version_code}'" not in identity:
        raise ValueError("apk version code does not match the release policy")
    if "application-debuggable" in badging:
        raise ValueError("distribution apk must not be debuggable")
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if not sdk or int(sdk[1]) != POLICY["min_sdk"] or not target or int(target[1]) < 35:
        raise ValueError("unexpected android sdk policy")
    allowed_permissions = {
        "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CHANGE_NETWORK_STATE", "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
        "android.permission.POST_NOTIFICATIONS", "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.WAKE_LOCK",
        package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    }
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badging, re.M))
    if permissions - allowed_permissions:
        raise ValueError("unexpected permissions: " + ", ".join(sorted(permissions - allowed_permissions)))
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("apk contains duplicate ZIP entries")
        if archive.testzip() is not None:
            raise ValueError("apk ZIP entry failed its CRC check")
        if any(Path(name).name in {"libwg.so", "libwg-quick.so"} for name in names):
            raise ValueError("unused root-management native tools must not ship")
        if not any(name.endswith("/libwg-go.so") for name in names):
            raise ValueError("amneziawg userspace backend missing")
        if not any(name.endswith("/libdeytt-awg.so") for name in names):
            raise ValueError("amneziawg domain-routing backend missing")
        if not any(name.endswith("/libopenflux_jni.so") for name in names):
            raise ValueError("OpenFlux transport backend missing")
    run(tool("zipalign"), "-c", "-P", "16", "4", str(apk))
    return identity


def verify(apk, qa=False, expected_version=None, expected_version_code=None):
    identity = inspect_apk(apk, qa, expected_version, expected_version_code)
    expected = normalize_fingerprint(POLICY["certificate_sha256"])
    # A cert in untrusted metadata is insufficient: apksigner verifies the APK.
    # Check older API levels, current API 28+, and Android 16 explicitly.
    for minimum, maximum in ((POLICY["min_sdk"], 27), (28, None), (36, 36)):
        args = [tool("apksigner"), "verify", "--print-certs", "--min-sdk-version", str(minimum)]
        if maximum is not None:
            args += ["--max-sdk-version", str(maximum)]
        output = run(*args, str(apk))
        fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", output, re.M | re.I)
        if [fingerprint.lower() for fingerprint in fingerprints] != [expected]:
            raise ValueError(f"Unexpected signing certificate for API {minimum}")
    print(identity)
    print(f"verified release policy and signatures: {apk.name}")
    with apk.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def sign(args):
    inspect_apk(args.apk, args.qa, args.expected_version, args.expected_version_code)
    if args.output.resolve() == args.apk.resolve():
        raise ValueError("signing must not overwrite the unsigned input")
    private_paths = [args.keystore, args.password_file]
    for private_path in private_paths:
        if private_path.resolve().is_relative_to(ROOT):
            raise ValueError("keep signing keys and passwords outside the source checkout")
        if not private_path.is_file():
            raise ValueError("signing credentials are unavailable")
    check_keystore_certificate(args.keystore, args.password_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".sign-", dir=args.output.parent) as temporary:
        output = Path(temporary) / args.output.name
        command = [
            tool("apksigner"), "sign", "--out", str(output),
            "--debuggable-apk-permitted", "false", "--v4-signing-enabled", "false",
        ]
        command += ["--ks", str(args.keystore), "--ks-key-alias", RELEASE_KEY_ALIAS,
                    "--ks-pass", "file:" + str(args.password_file)]
        run(*command, str(args.apk))
        digest = verify(output, args.qa, args.expected_version, args.expected_version_code)
        output.replace(args.output)
    args.output.with_suffix(".apk.sha256").write_text(f"{digest}  {args.output.name}\n")
    print(f"SHA-256: {digest}")


def bundle(args):
    if args.output.resolve() == args.apk.resolve():
        raise ValueError("bundle output must not overwrite the APK")
    if args.output.suffix.lower() != ".zip":
        raise ValueError("install bundle output must be a .zip file")
    digest = verify(args.apk, expected_version=args.expected_version,
                    expected_version_code=args.expected_version_code)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".bundle-", dir=args.output.parent) as temporary:
        output = Path(temporary) / args.output.name
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(args.apk, "app-universal-release.apk")
            archive.writestr("app-universal-release.apk.sha256",
                             f"{digest}  app-universal-release.apk\n")
        with zipfile.ZipFile(output) as archive:
            if archive.namelist() != ["app-universal-release.apk", "app-universal-release.apk.sha256"]:
                raise ValueError("install bundle contains unexpected ZIP entries")
            if archive.testzip() is not None:
                raise ValueError("install bundle ZIP entry failed its CRC check")
            with archive.open("app-universal-release.apk") as source:
                if hashlib.file_digest(source, "sha256").hexdigest() != digest:
                    raise ValueError("bundled APK changed after signature verification")
        output.replace(args.output)
    print(f"created install bundle: {args.output.name}")


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("sign", "verify", "bundle"):
        command = commands.add_parser(name)
        command.add_argument("apk", type=Path)
        command.add_argument("--expected-version")
        command.add_argument("--expected-version-code", type=int)
        if name in ("sign", "verify"):
            command.add_argument("--qa", action="store_true", help="allow the isolated .qa package only")
        if name == "sign":
            command.add_argument("--output", type=Path, required=True)
            command.add_argument("--keystore", type=Path, required=True)
            command.add_argument("--password-file", type=Path, required=True)
        elif name == "bundle":
            command.add_argument("--output", type=Path, required=True)
    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    try:
        if args.command == "sign":
            sign(args)
        elif args.command == "bundle":
            bundle(args)
        else:
            print("SHA-256: " + verify(
                args.apk, args.qa, args.expected_version, args.expected_version_code,
            ))
    except subprocess.CalledProcessError as error:
        # Passwords are passed by file/env reference, never command arguments.
        parser.exit(1, f"release verification failed: {error.stderr.strip()}\n")
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"release verification failed: {error}\n")


if __name__ == "__main__":
    main()

