"""Prepare an isolated, pinned BOOTTIME Go SDK for the Windows Android build."""
import argparse
from pathlib import Path
import shutil
import subprocess


def prepare(go_executable: str, destination: Path, patch: Path) -> None:
    version = subprocess.check_output([go_executable, "version"], text=True).strip()
    if not version.startswith("go version go1.25.14 windows/"):
        raise ValueError("AWG Windows builds require the reviewed Go 1.25.14 SDK")
    sdk = Path(subprocess.check_output([go_executable, "env", "GOROOT"], text=True).strip())
    marker = destination / ".deytt-boottime"
    import hashlib
    identity = hashlib.sha256(patch.read_bytes()).hexdigest()
    if marker.is_file() and marker.read_text().strip() == identity:
        return
    if destination.exists() and any(destination.iterdir()):
        # Gradle may remove a previously declared marker as a stale task output.
        # Recover only a version-matching SDK whose runtime patch is already exact.
        executable = destination / "bin/go.exe"
        if (executable.is_file()
                and hashlib.sha256(executable.read_bytes()).digest() == hashlib.sha256(Path(go_executable).read_bytes()).digest()
                and subprocess.run(["git", "-C", str(destination), "apply", "--reverse", "--check", str(patch.resolve())],
                                   capture_output=True).returncode == 0):
            marker.write_text(identity + "\n")
            return
        raise ValueError("Existing incomplete AWG SDK: choose a fresh build directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Gradle creates declared output directories before running the task.
    shutil.copytree(sdk, destination, dirs_exist_ok=True)
    subprocess.run(["git", "-C", str(destination), "apply", "--check", str(patch.resolve())], check=True)
    subprocess.run(["git", "-C", str(destination), "apply", str(patch.resolve())], check=True)
    marker.write_text(identity + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--patch", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.go, args.output, args.patch)
