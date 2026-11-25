"""Official client import wrappers; existing subscription authorization stays authoritative."""
from __future__ import annotations

import hashlib
from contextlib import closing
import os
from pathlib import Path
import re
import sqlite3
import time
from urllib.parse import urlsplit

import requests


class LinkEncryptionError(RuntimeError):
    pass


def _validate_url(url: str) -> None:
    value = urlsplit(url)
    if value.scheme != "https" or not value.hostname or value.username or value.password:
        raise ValueError("an HTTPS subscription URL is required")
    if len(url) > 4096 or any(ord(c) < 32 for c in url):
        raise ValueError("invalid subscription URL")


def happ_link(url: str, *, cache: Path | None = None, post=None, now=None) -> str:
    """Request crypt5 from Happ. Errors never fall back to an unencrypted import."""
    _validate_url(url)
    cache = cache or Path(__file__).with_name("happ_links.db")
    stamp = int(time.time() if now is None else now)
    key = hashlib.sha256(url.encode()).hexdigest()
    with closing(sqlite3.connect(cache, timeout=3, isolation_level=None)) as db:
        os.chmod(cache, 0o600)
        db.execute("CREATE TABLE IF NOT EXISTS links (key TEXT PRIMARY KEY, link TEXT NOT NULL, ts INTEGER NOT NULL)")
        row = db.execute("SELECT link FROM links WHERE key=? AND ts>?", (key, stamp - 86400)).fetchone()
        if row and _valid_happ(row[0]):
            return row[0]
    try:
        response = (post or requests.post)(
            "https://crypto.happ.su/api-v2.php", json={"url": url}, timeout=(3, 10),
        )
        response.raise_for_status()
        data = response.json()
        link = data.get("encrypted_link") if isinstance(data, dict) else None
        if not _valid_happ(link):
            raise ValueError("unsupported encryption response")
    except (requests.RequestException, ValueError, TypeError):
        # Do not include request exceptions: they may contain a subscription URL.
        raise LinkEncryptionError("Happ link encryption is temporarily unavailable") from None
    with closing(sqlite3.connect(cache, timeout=3, isolation_level=None)) as db:
        db.execute("DELETE FROM links WHERE ts<?", (stamp - 7 * 86400,))
        db.execute("INSERT OR REPLACE INTO links VALUES (?,?,?)", (key, link, stamp))
    return link


def _valid_happ(link) -> bool:
    return isinstance(link, str) and bool(re.fullmatch(r"happ://crypt5/[A-Za-z0-9_+/=-]{20,16000}", link))


def incy_link(url: str, *, name: str = "Ghostly VPN") -> str:
    """Official crypt1 obfuscation. Its shared published key is not a secrecy boundary."""
    _validate_url(url)
    from incy_link_encoder import encrypt_link
    return encrypt_link(url, name=name)
