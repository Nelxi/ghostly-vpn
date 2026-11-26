#!/usr/bin/env python3
"""Restore subscription endpoints to MSK without restarting VPN services."""
import argparse
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import shutil
import tempfile


def restore(path: Path, check: bool = False) -> None:
    with path.with_name(path.name + ".cascade.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        raw = path.read_bytes()
        before = path.stat()
        config = json.loads(raw)
        vpn = config["vpn"]
        vpn["fi"].update(addr="45.10.219.94:443", addr_ip="45.10.219.94")
        vpn["de"]["host"] = vpn["nl"]["host"] = "45.10.219.94"
        vpn["hysteria2"]["addr"] = "45.10.219.94:443"
        vpn["hysteria2"]["obfs"]["addr"] = "45.10.219.94:24443"
        vpn["de"]["hy2_addr"] = "45.10.219.94:24444"
        vpn["de"]["hy2_obfs"]["addr"] = "45.10.219.94:24445"
        vpn["nl"]["hy2_addr"] = "45.10.219.94:24446"
        vpn["nl"]["hy2_obfs"]["addr"] = "45.10.219.94:24447"
        if check:
            print("CHECK OK: FI/DE/NL TCP and Hysteria endpoints can be restored to MSK")
            return
        if config == json.loads(raw):
            print("All cascade endpoints already point to MSK")
            return
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        backup = path.with_name(path.name + ".bak.scheduled-msk-" + stamp)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                             prefix=".cascade-", delete=False) as stream:
                temporary = Path(stream.name)
                os.fchmod(stream.fileno(), before.st_mode & 0o777)
                os.fchown(stream.fileno(), before.st_uid, before.st_gid)
                json.dump(config, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            if path.read_bytes() != raw or path.stat().st_mtime_ns != before.st_mtime_ns:
                raise RuntimeError("Config changed concurrently; refusing to overwrite it")
            shutil.copy2(path, backup)
            # Recheck after backup too; other configuration writers use atomic replacement.
            if path.read_bytes() != raw or path.stat().st_mtime_ns != before.st_mtime_ns:
                raise RuntimeError("Config changed during backup; refusing to overwrite it")
            temporary.replace(path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        print("Restored FI/DE/NL TCP and Hysteria through MSK; backup:", backup.name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("/root/shadevpn/ghostlink_config.json"))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    restore(args.config, args.check)
