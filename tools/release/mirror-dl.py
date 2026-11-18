#!/usr/bin/env python3
"""Mirror a published GitHub release from the DL server using the publisher's manifest hash."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory


RELEASES = "https://github.com/Nelxi/ghostly-vpn/releases/download"
ASSETS = (
    "Ghostly-Android.apk",
    "Ghostly-Android-armv7.apk",
    "Ghostly-Android-universal.apk",
    "Ghostly-Windows.exe",
    "Ghostly-Windows-Portable.zip",
)
RANGE_THRESHOLD = 128 * 1024 * 1024
RANGE_BYTES = 16 * 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def version_key(value):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
        raise ValueError(f"Invalid release version: {value}")
    return tuple(map(int, value.split(".")))


def fetch(url, destination, bounds=None):
    command = [
        "curl", "--fail", "--silent", "--show-error", "--location",
        "--retry", "3", "--connect-timeout", "10", "--max-time", "180",
        "--output", str(destination),
    ]
    if bounds is None:
        subprocess.run([*command, url], check=True)
        return
    start, end = bounds
    length = end - start + 1
    status = subprocess.check_output(
        [*command, "--range", f"{start}-{end}", "--max-filesize", str(length), "--write-out", "%{http_code}", url],
        text=True,
    )
    if status != "206" or destination.stat().st_size != length:
        raise ValueError(f"Invalid HTTP range {start}-{end}")


def fetch_asset(url, path, size):
    if size < RANGE_THRESHOLD:
        fetch(url, path)
        return

    # A single GitHub CDN connection can throttle a large APK. Smaller ranges retry independently.
    ranges = [(start, min(start + RANGE_BYTES, size) - 1) for start in range(0, size, RANGE_BYTES)]
    with TemporaryDirectory(prefix=".parts-", dir=path.parent) as directory:
        def download(bounds):
            part = Path(directory) / str(bounds[0])
            fetch(url, part, bounds)
            return part

        with ThreadPoolExecutor(max_workers=6) as pool:
            parts = list(pool.map(download, ranges))
        with path.open("wb") as sink:
            for part in parts:
                with part.open("rb") as stream:
                    shutil.copyfileobj(stream, sink)


def verify(path, metadata):
    if path.stat().st_size != metadata["size"] or sha256(path) != metadata["sha256"]:
        raise ValueError(f"Size or SHA-256 mismatch: {path.name}")


def mirror(root, version, manifest_hash):
    wanted_version = version_key(version)
    if not re.fullmatch("[0-9a-f]{64}", manifest_hash):
        raise ValueError("Expected the SHA-256 of the locally generated latest.json")
    release_url = f"{RELEASES}/v{version}"
    asset_dir = root / version
    with (root / ".publish.lock").open("a") as lock, TemporaryDirectory(prefix=".release-", dir=root) as directory:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = root / "latest.json"
        if current.exists() and version_key(json.loads(current.read_bytes())["version"]) > wanted_version:
            raise ValueError("The DL mirror already serves a newer release")

        stage = Path(directory) / version
        stage.mkdir(mode=0o755)
        manifest_path = stage / "latest.json"
        fetch(f"{release_url}/latest.json", manifest_path)
        if sha256(manifest_path) != manifest_hash:
            raise ValueError("The GitHub manifest does not match the locally generated manifest")
        manifest = json.loads(manifest_path.read_bytes())
        if manifest["version"] != version or set(manifest["files"]) != set(ASSETS):
            raise ValueError("Manifest version or asset list does not match the release")
        files = manifest["files"]
        for name in ASSETS:
            entry = files[name]
            if entry["version"] != version or type(entry["size"]) is not int or entry["size"] <= 0:
                raise ValueError(f"Invalid metadata for {name}")
            if not re.fullmatch("[0-9a-f]{64}", entry["sha256"]):
                raise ValueError(f"Invalid SHA-256 for {name}")

        if asset_dir.exists():
            if sha256(asset_dir / "latest.json") != manifest_hash:
                raise ValueError(f"Different release already exists at {asset_dir}")
            for name in ASSETS:
                verify(asset_dir / name, files[name])
        else:
            def download(name):
                path = stage / name
                fetch_asset(f"{release_url}/{name}", path, files[name]["size"])
                verify(path, files[name])
                print(f"Verified {name}", flush=True)

            with ThreadPoolExecutor(max_workers=len(ASSETS)) as pool:
                list(pool.map(download, ASSETS))
            (stage / "SHA256SUMS.txt").write_text(
                "".join(f'{files[name]["sha256"]}  {name}\n' for name in ASSETS), encoding="ascii",
            )
            for path in stage.iterdir():
                path.chmod(0o644)
            stage.chmod(0o755)
            stage.rename(asset_dir)

        # Update the manifest last. Clients only see the new version once every asset is in place.
        for name in (*ASSETS, "SHA256SUMS.txt", "latest.json"):
            link = Path(directory) / name
            link.symlink_to(Path(version) / name)
            os.replace(link, root / name)
        print(f"DL mirror serves {version}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("manifest_sha256")
    parser.add_argument("--root", type=Path, default=Path("/var/www/ghostly-dl"))
    args = parser.parse_args()
    mirror(args.root.resolve(strict=True), args.version, args.manifest_sha256)
