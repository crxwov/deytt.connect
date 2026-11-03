#!/usr/bin/env python3
"""
Publishes a pre-release to GitHub for deytt.connect.
"""
import json
import mimetypes
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "crxwov/deytt.connect"
VERSION = "0.11.112"
TAG = f"v{VERSION}"
RELEASE_NAME = f"connect./{VERSION}"

BODY = """### что изменилось:
- многопоточное скачивание обновлений: пакеты обновлений загружаются параллельными сегментами напрямую с быстрого сервера
- значительно увеличена скорость скачивания при слабом сигнале и сетевых ограничениях
- автоматическая проверка целостности и непрерывное отображение точного прогресса
- повышенная общая стабильность обновления приложения

---
- `app-universal-release.apk` — универсальный подписанный APK для Android (arm64-v8a, armeabi-v7a, x86_64)."""


def get_github_token() -> str:
    proc = subprocess.Popen(
        ["git", "credential", "fill"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        text=True,
    )
    out, _ = proc.communicate("protocol=https\nhost=github.com\n\n")
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    raise RuntimeError("GitHub token not found in git credentials")


def main():
    token = get_github_token()
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "deytt-release-agent",
    }

    print(f"Checking if release {TAG} exists...")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}",
        headers=headers,
    )
    release = None
    try:
        with urllib.request.urlopen(req) as resp:
            release = json.load(resp)
            print(f"Found existing release id: {release['id']}")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print("Release does not exist yet.")
        else:
            raise

    if not release:
        print(f"Creating pre-release {RELEASE_NAME} (tag: {TAG})...")
        payload = json.dumps(
            {
                "tag_name": TAG,
                "target_commitish": "main",
                "name": RELEASE_NAME,
                "body": BODY,
                "draft": False,
                "prerelease": True,
            }
        ).encode("utf-8")
        create_req = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/releases",
            data=payload,
            headers={**headers, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(create_req) as resp:
            release = json.load(resp)
            print(f"Created release id: {release['id']}")
    else:
        # Update body and prerelease flag if exists
        print(f"Updating release {release['id']}...")
        payload = json.dumps(
            {
                "name": RELEASE_NAME,
                "body": BODY,
                "draft": False,
                "prerelease": True,
            }
        ).encode("utf-8")
        update_req = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/releases/{release['id']}",
            data=payload,
            headers={**headers, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(update_req) as resp:
            release = json.load(resp)
            print(f"Updated release id: {release['id']}")

    upload_url_base = release["upload_url"].split("{")[0]

    root = Path(__file__).resolve().parent.parent / "output" / "release"
    files = [
        root / "app-universal-release.apk",
        root / "app-universal-release.apk.sha256",
    ]

    existing_assets = {a["name"]: a["id"] for a in release.get("assets", [])}

    for file_path in files:
        if not file_path.is_file():
            print(f"WARNING: File {file_path} not found, skipping.")
            continue

        name = file_path.name
        if name in existing_assets:
            print(f"Asset {name} already exists (id: {existing_assets[name]}), deleting old asset...")
            del_req = urllib.request.Request(
                f"https://api.github.com/repos/{REPO}/releases/assets/{existing_assets[name]}",
                headers=headers,
                method="DELETE",
            )
            with urllib.request.urlopen(del_req) as resp:
                print(f"Deleted old asset {name}")

        size = file_path.stat().st_size
        mime = mimetypes.guess_type(str(file_path))[0] or "application/octet-stream"
        if file_path.suffix == ".apk":
            mime = "application/vnd.android.package-archive"
        elif file_path.suffix == ".sha256":
            mime = "text/plain"

        print(f"Uploading {name} ({size} bytes, mime: {mime})...")
        upload_url = f"{upload_url_base}?name={name}"
        with open(file_path, "rb") as f:
            data = f.read()

        up_req = urllib.request.Request(
            upload_url,
            data=data,
            headers={
                **headers,
                "Content-Type": mime,
                "Content-Length": str(size),
            },
        )
        with urllib.request.urlopen(up_req) as resp:
            asset = json.load(resp)
            print(f"Uploaded {name} -> {asset['browser_download_url']}")

    print("\nPre-release published successfully!")
    print(f"URL: {release['html_url']}")


if __name__ == "__main__":
    main()
