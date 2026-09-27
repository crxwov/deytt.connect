#!/usr/bin/env python3
"""Sign and verify distribution APKs; private keys always stay outside the repo."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
POLICY = json.loads((ROOT / "release/signing-policy.json").read_text())


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME", ROOT / ".toolchain/android-sdk"))
    path = sdk / "build-tools/35.0.0" / name
    if not path.is_file():
        raise ValueError(f"Install Android build-tools 35.0.0: missing {name}")
    return str(path)


def inspect_apk(apk, qa=False):
    badging = run(tool("aapt"), "dump", "badging", str(apk))
    package = POLICY["package"] + (".qa" if qa else "")
    if not badging.startswith(f"package: name='{package}' "):
        raise ValueError("Unexpected package identity")
    if "application-debuggable" in badging:
        raise ValueError("Distribution APK must not be debuggable")
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if not sdk or int(sdk[1]) != POLICY["min_sdk"] or not target or int(target[1]) < 35:
        raise ValueError("Unexpected Android SDK policy")
    allowed_permissions = {
        "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CHANGE_NETWORK_STATE", "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
        "android.permission.POST_NOTIFICATIONS", "android.permission.REQUEST_INSTALL_PACKAGES",
        package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    }
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badging, re.M))
    if permissions - allowed_permissions:
        raise ValueError("Unexpected permissions: " + ", ".join(sorted(permissions - allowed_permissions)))
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if any(Path(name).name in {"libwg.so", "libwg-quick.so"} for name in names):
            raise ValueError("Unused root-management native tools must not ship")
        if not any(name.endswith("/libwg-go.so") for name in names):
            raise ValueError("AmneziaWG userspace backend missing")
        if not any(name.endswith("/libdeytt-awg.so") for name in names):
            raise ValueError("AmneziaWG domain-routing backend missing")
    run(tool("zipalign"), "-c", "-P", "16", "4", str(apk))
    return badging.splitlines()[0]


def verify(apk, qa=False):
    identity = inspect_apk(apk, qa)
    # Independently validate the legacy and rotated scheme. A cert in untrusted
    # metadata is insufficient: apksigner must verify the actual APK signatures.
    for minimum, maximum, expected in (
        (24, 27, POLICY["legacy_certificate_sha256"]),
        (28, None, POLICY["release_certificate_sha256"]),
    ):
        args = [tool("apksigner"), "verify", "--print-certs", "--min-sdk-version", str(minimum)]
        if maximum is not None:
            args += ["--max-sdk-version", str(maximum)]
        output = run(*args, str(apk))
        fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", output, re.M)
        if fingerprints != [expected]:
            raise ValueError(f"Unexpected signing certificate for API {minimum}")
    print(identity)
    print(f"Verified release policy and signatures: {apk.name}")
    with apk.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def sign(args):
    inspect_apk(args.apk, args.qa)
    if args.output.resolve() == args.apk.resolve():
        raise ValueError("Signing must not overwrite the unsigned input")
    for private_path in (args.keystore, args.password_file, args.legacy_keystore):
        if private_path.resolve().is_relative_to(ROOT):
            raise ValueError("Keep signing keys and passwords outside the source checkout")
        if not private_path.is_file():
            raise ValueError("Signing credentials are unavailable")
    if args.legacy_password_env not in os.environ:
        raise ValueError("Legacy keystore password environment variable is missing")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".sign-", dir=args.output.parent) as temporary:
        output = Path(temporary) / args.output.name
        run(
            tool("apksigner"), "sign", "--out", str(output),
            "--debuggable-apk-permitted", "false", "--v4-signing-enabled", "false",
            "--rotation-min-sdk-version", str(POLICY["rotation_min_sdk"]),
            "--lineage", str(ROOT / "release/signing-lineage.bin"),
            "--ks", str(args.legacy_keystore), "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "env:" + args.legacy_password_env,
            "--next-signer", "--ks", str(args.keystore), "--ks-key-alias", "deytt-connect",
            "--ks-pass", "file:" + str(args.password_file), str(args.apk),
        )
        digest = verify(output, args.qa)
        output.replace(args.output)
    args.output.with_suffix(".apk.sha256").write_text(f"{digest}  {args.output.name}\n")
    print(f"SHA-256: {digest}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("sign", "verify"):
        command = commands.add_parser(name)
        command.add_argument("apk", type=Path)
        command.add_argument("--qa", action="store_true", help="Allow the isolated .qa package only")
        if name == "sign":
            command.add_argument("--output", type=Path, required=True)
            command.add_argument("--keystore", type=Path, required=True)
            command.add_argument("--password-file", type=Path, required=True)
            command.add_argument("--legacy-keystore", type=Path, required=True)
            command.add_argument("--legacy-password-env", required=True)
    args = parser.parse_args()
    try:
        if args.command == "sign":
            sign(args)
        else:
            print("SHA-256: " + verify(args.apk, args.qa))
    except subprocess.CalledProcessError as error:
        # Passwords are passed by file/env reference, never command arguments.
        parser.exit(1, f"Release verification failed: {error.stderr.strip()}\n")
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Release verification failed: {error}\n")


if __name__ == "__main__":
    main()
