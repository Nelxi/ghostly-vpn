#!/usr/bin/env python3
"""Android Key Attestation for Ghostly trials (server side).

A trial subscription is handed to any client that sends a device id. Our own Android app used to be
trusted blindly on top of that: a re-packed APK (patched dex) could say anything about the device and
sit on trials for weeks. Here the app has to prove, with a key generated inside the phone's secure
hardware, that

  * the certificate chain of that key ends in a Google hardware attestation root and none of its
    certificates is on Google's revocation list;
  * the key carries the one-time challenge this server issued for this subscription and device;
  * the package that asked for the key is ``app.ghostly.vpn`` signed with OUR release certificate
    (this is what a modified APK cannot fake: the OS fills it in, not the app);
  * the key lives in a TEE / StrongBox, the bootloader is locked and the system image is verified
    (an unlocked or re-flashed phone is what root needs).

Only the trial path of ``/sub/<id>`` calls this, and only when the client presents itself as the
Ghostly app on Android. Paid subscriptions and other clients never get here.

Works with cryptography 3.4 (Ubuntu 22.04) and newer.
"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import secrets
import sqlite3
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

log = logging.getLogger("ghostlink.attest")

ATTEST_OID = "1.3.6.1.4.1.11129.2.1.17"
ROOTS_URL = "https://android.googleapis.com/attestation/root"
STATUS_URL = "https://android.googleapis.com/attestation/status"

# KeyDescription.attestationSecurityLevel
LEVEL_SOFTWARE, LEVEL_TEE, LEVEL_STRONGBOX = 0, 1, 2
# RootOfTrust.verifiedBootState
BOOT_VERIFIED = 0
# AuthorizationList tags
TAG_ROOT_OF_TRUST = 704
TAG_APPLICATION_ID = 709

CHALLENGE_TTL = 15 * 60
CHALLENGE_BYTES = 32


class AttestError(Exception):
    """Refusal with a short machine reason (logged and stored, shown to the user as a category)."""

    def __init__(self, reason: str, detail: str = ""):
        super().__init__(f"{reason}: {detail}" if detail else reason)
        self.reason = reason
        self.detail = detail


# --------------------------------------------------------------------------- DER

@dataclass
class Tlv:
    cls: int          # 0 universal, 1 application, 2 context, 3 private
    constructed: bool
    tag: int
    value: bytes


def _read(data: bytes, pos: int) -> tuple[Tlv, int]:
    if pos >= len(data):
        raise ValueError("truncated")
    first = data[pos]
    pos += 1
    tag = first & 0x1F
    if tag == 0x1F:  # high tag number form: base-128, big endian
        tag = 0
        for _ in range(5):
            if pos >= len(data):
                raise ValueError("truncated tag")
            b = data[pos]
            pos += 1
            tag = (tag << 7) | (b & 0x7F)
            if not b & 0x80:
                break
        else:
            raise ValueError("tag too long")
    if pos >= len(data):
        raise ValueError("truncated length")
    length = data[pos]
    pos += 1
    if length & 0x80:
        n = length & 0x7F
        if n == 0 or n > 4 or pos + n > len(data):
            raise ValueError("bad length")
        length = int.from_bytes(data[pos:pos + n], "big")
        pos += n
    if pos + length > len(data):
        raise ValueError("value runs past the end")
    return Tlv(first >> 6, bool(first & 0x20), tag, data[pos:pos + length]), pos + length


def _children(data: bytes) -> list[Tlv]:
    out, pos = [], 0
    while pos < len(data):
        item, pos = _read(data, pos)
        out.append(item)
    return out


def _one(data: bytes) -> Tlv:
    item, end = _read(data, 0)
    if end != len(data):
        raise ValueError("trailing bytes")
    return item


def _universal(item: Tlv, tag: int, what: str) -> bytes:
    if item.cls != 0 or item.tag != tag:
        raise ValueError(f"{what}: unexpected tag {item.cls}/{item.tag}")
    return item.value


def _int(item: Tlv, what: str) -> int:
    if item.cls != 0 or item.tag not in (2, 10):  # INTEGER or ENUMERATED
        raise ValueError(f"{what}: not an integer")
    return int.from_bytes(item.value, "big", signed=True)


# --------------------------------------------------------------------------- attestation record

@dataclass
class Record:
    attestation_version: int
    security_level: int
    keymaster_level: int
    challenge: bytes
    packages: list[tuple[str, int]] = field(default_factory=list)
    signature_digests: list[bytes] = field(default_factory=list)
    has_root_of_trust: bool = False
    device_locked: bool = False
    verified_boot_state: int = -1


def _auth_list(item: Tlv) -> dict[int, bytes]:
    """AuthorizationList ::= SEQUENCE of [tag] EXPLICIT values -> {tag: inner DER}."""
    out: dict[int, bytes] = {}
    for f in _children(_universal(item, 16, "authorization list")):
        if f.cls == 2:
            out[f.tag] = f.value
    return out


def parse_record(ext: bytes) -> Record:
    """Parses the KeyDescription extension by its real structure (no guessing by scanning)."""
    kids = _children(_universal(_one(ext), 16, "key description"))
    if len(kids) < 8:
        raise ValueError("key description is too short")
    rec = Record(
        attestation_version=_int(kids[0], "attestationVersion"),
        security_level=_int(kids[1], "attestationSecurityLevel"),
        keymaster_level=_int(kids[3], "keymasterSecurityLevel"),
        challenge=_universal(kids[4], 4, "attestationChallenge"),
    )
    software, hardware = _auth_list(kids[6]), _auth_list(kids[7])

    # The application id is written by the Android system (softwareEnforced on every device).
    raw = software.get(TAG_APPLICATION_ID) or hardware.get(TAG_APPLICATION_ID)
    if raw is not None:
        app_id = _children(_universal(_one(_universal(_one(raw), 4, "attestationApplicationId")), 16, "application id"))
        if len(app_id) >= 2:
            for pkg in _children(_universal(app_id[0], 17, "package set")):
                p = _children(_universal(pkg, 16, "package info"))
                rec.packages.append((_universal(p[0], 4, "package name").decode("utf-8"), _int(p[1], "package version")))
            for d in _children(_universal(app_id[1], 17, "digest set")):
                rec.signature_digests.append(_universal(d, 4, "signature digest"))

    # The root of trust counts only from the hardware-enforced list: software can claim anything.
    raw = hardware.get(TAG_ROOT_OF_TRUST)
    if raw is not None:
        rot = _children(_universal(_one(raw), 16, "root of trust"))
        if len(rot) >= 3:
            rec.has_root_of_trust = True
            rec.device_locked = _universal(rot[1], 1, "deviceLocked") != b"\x00"
            rec.verified_boot_state = _int(rot[2], "verifiedBootState")
    return rec


# --------------------------------------------------------------------------- chain

def _spki(cert: x509.Certificate) -> bytes:
    return cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def _verify_signature(child: x509.Certificate, parent: x509.Certificate) -> None:
    pub = parent.public_key()
    if isinstance(pub, ec.EllipticCurvePublicKey):
        pub.verify(child.signature, child.tbs_certificate_bytes, ec.ECDSA(child.signature_hash_algorithm))
    elif isinstance(pub, rsa.RSAPublicKey):
        pub.verify(child.signature, child.tbs_certificate_bytes, padding.PKCS1v15(), child.signature_hash_algorithm)
    else:
        raise ValueError("unsupported issuer key")


def verify_chain(chain: Sequence[x509.Certificate], roots: Sequence[x509.Certificate], revoked: Iterable[str]) -> None:
    """Leaf first. Every certificate signed by the next one, the last one is a pinned Google root."""
    if not 2 <= len(chain) <= 10:
        raise AttestError("chain", f"length {len(chain)}")
    root_keys = {_spki(r) for r in roots}
    if _spki(chain[-1]) not in root_keys:
        raise AttestError("chain", "does not end in a Google attestation root")
    for i, cert in enumerate(chain):
        parent = chain[i + 1] if i + 1 < len(chain) else cert  # the root signs itself
        try:
            _verify_signature(cert, parent)
        except Exception as e:  # InvalidSignature and friends
            raise AttestError("chain", f"bad signature at {i}: {type(e).__name__}")
        if i + 1 < len(chain) and cert.issuer != parent.subject:
            raise AttestError("chain", f"issuer mismatch at {i}")
        # Validity dates are deliberately not checked. Phones with a factory keybox (most of them)
        # carry intermediates issued 2016-05-26 … 2026-05-24: all "expired" by now, yet these are the
        # keys the hardware will use for its whole life, and Google's own answer to a leaked one is the
        # revocation list below, not the date. The leaf's dates (1970…2106) never meant anything.
    bad = {str(s).lower().lstrip("0") for s in revoked}
    for i, cert in enumerate(chain):
        if (f"{cert.serial_number:x}".lstrip("0") in bad) or (str(cert.serial_number) in bad):
            raise AttestError("revoked", f"certificate {i}")


@dataclass
class Policy:
    package: str
    cert_sha256: Sequence[bytes]
    min_version_code: int = 0


def decide(chain_der: Sequence[bytes], challenge: bytes, policy: Policy, roots: Sequence[x509.Certificate],
           revoked: Iterable[str]) -> Record:
    """Returns the parsed record when everything holds, raises [AttestError] otherwise."""
    try:
        chain = [x509.load_der_x509_certificate(c) for c in chain_der]
    except Exception as e:
        raise AttestError("chain", f"unreadable certificate: {type(e).__name__}")
    verify_chain(chain, roots, revoked)
    try:
        ext = chain[0].extensions.get_extension_for_oid(x509.ObjectIdentifier(ATTEST_OID))
        rec = parse_record(ext.value.value)
    except x509.ExtensionNotFound:
        raise AttestError("no_attestation", "the key has no attestation record")
    except AttestError:
        raise
    except Exception as e:
        raise AttestError("parse", f"{type(e).__name__}: {e}")
    if not secrets.compare_digest(rec.challenge, challenge):
        raise AttestError("challenge", "the key was made for another challenge")
    names = [n for n, _ in rec.packages]
    if names != [policy.package]:
        raise AttestError("modified", f"package {names}")
    if not set(rec.signature_digests) & set(policy.cert_sha256):
        raise AttestError("modified", "signed with " + ",".join(d.hex()[:16] for d in rec.signature_digests))
    if policy.min_version_code and rec.packages[0][1] < policy.min_version_code:
        raise AttestError("old_app", f"version code {rec.packages[0][1]}")
    if rec.security_level not in (LEVEL_TEE, LEVEL_STRONGBOX) or rec.keymaster_level not in (LEVEL_TEE, LEVEL_STRONGBOX):
        raise AttestError("software_key", f"levels {rec.security_level}/{rec.keymaster_level}")
    if not rec.has_root_of_trust:
        raise AttestError("rooted", "no hardware root of trust")
    if not rec.device_locked or rec.verified_boot_state != BOOT_VERIFIED:
        raise AttestError("rooted", f"locked={rec.device_locked} boot={rec.verified_boot_state}")
    return rec


# --------------------------------------------------------------------------- service (state + Google lists)

class Attestation:
    """Challenges, verdicts and the cached Google lists. One instance per process, thread-safe."""

    def __init__(self, db_path: str | os.PathLike, cache_dir: str | os.PathLike, policy: Policy):
        self.db_path = str(db_path)
        self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.policy = policy
        self._lock = threading.Lock()
        self._roots: list[x509.Certificate] = []
        self._revoked: set[str] = set()
        self._loaded_at = 0.0
        with self._db() as db:
            db.executescript(
                """
                create table if not exists challenge (
                    id text primary key, sub_id text not null, hwid text not null,
                    nonce blob not null, created_at integer not null);
                create index if not exists challenge_created on challenge(created_at);
                create table if not exists verdict (
                    sub_id text not null, hwid text not null, ok integer not null, reason text not null,
                    detail text, at integer not null, ip text, model text,
                    primary key (sub_id, hwid));
                """
            )

    def _db(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.db_path, timeout=5, isolation_level=None)
        db.execute("pragma journal_mode=wal")
        return db

    # ---- Google roots and revocation list: fetched once a day, the last good copy kept on disk.

    def _fetch(self, url: str, name: str, max_age: float) -> bytes | None:
        path = self.cache_dir / name
        fresh = path.exists() and time.time() - path.stat().st_mtime < max_age
        if not fresh:
            try:
                with urllib.request.urlopen(url, timeout=8) as r:
                    data = r.read(8 * 1024 * 1024)
                json.loads(data)  # never replace a good copy with an error page
                tmp = path.with_suffix(".tmp")
                tmp.write_bytes(data)
                tmp.replace(path)
            except Exception as e:
                log.warning("attest: could not refresh %s: %s", name, e)
                if path.exists():
                    os.utime(path, None)  # try again after max_age, not on every request
        return path.read_bytes() if path.exists() else None

    def _lists(self) -> tuple[list[x509.Certificate], set[str]]:
        with self._lock:
            if self._roots and time.time() - self._loaded_at < 3600:
                return self._roots, self._revoked
            roots_raw = self._fetch(ROOTS_URL, "google-roots.json", 7 * 86400)
            status_raw = self._fetch(STATUS_URL, "google-status.json", 86400)
            if roots_raw:
                self._roots = [x509.load_pem_x509_certificate(p.encode()) for p in json.loads(roots_raw)]
            if status_raw:
                self._revoked = {str(k).lower() for k in (json.loads(status_raw).get("entries") or {})}
            self._loaded_at = time.time()
            return self._roots, self._revoked

    # ---- challenges

    def challenge(self, sub_id: str, hwid: str) -> tuple[str, str]:
        """A fresh one-time challenge for this subscription and device: (id, base64 nonce)."""
        cid, nonce = secrets.token_urlsafe(18), secrets.token_bytes(CHALLENGE_BYTES)
        now = int(time.time())
        with self._db() as db:
            db.execute("delete from challenge where created_at < ?", (now - CHALLENGE_TTL,))
            db.execute("insert into challenge values (?,?,?,?,?)", (cid, sub_id, hwid, nonce, now))
        return cid, base64.b64encode(nonce).decode("ascii")

    def _take_challenge(self, cid: str, sub_id: str, hwid: str) -> bytes:
        with self._db() as db:
            db.execute("begin immediate")
            row = db.execute("select sub_id, hwid, nonce, created_at from challenge where id=?", (cid,)).fetchone()
            db.execute("delete from challenge where id=?", (cid,))  # one use, whatever comes next
            db.execute("commit")
        if row is None:
            raise AttestError("challenge", "unknown or already used")
        if row[0] != sub_id or row[1] != hwid:
            raise AttestError("challenge", "issued for another device")
        if time.time() - row[3] > CHALLENGE_TTL:
            raise AttestError("challenge", "expired")
        return bytes(row[2])

    # ---- verdicts

    def verdict(self, sub_id: str, hwid: str) -> tuple[bool, str, int] | None:
        """The stored answer for this device on this subscription — (ok, reason, unix time) — or None
        when it never tried."""
        with self._db() as db:
            row = db.execute("select ok, reason, at from verdict where sub_id=? and hwid=?", (sub_id, hwid)).fetchone()
        return (bool(row[0]), row[1], int(row[2])) if row else None

    def preload(self) -> None:
        """Fetches Google's lists in the background, so the first check doesn't wait for the network."""
        threading.Thread(target=lambda: self._lists(), name="attest-preload", daemon=True).start()

    def _store(self, sub_id: str, hwid: str, ok: bool, reason: str, detail: str, ip: str, model: str) -> None:
        with self._db() as db:
            db.execute(
                "insert or replace into verdict values (?,?,?,?,?,?,?,?)",
                (sub_id, hwid, int(ok), reason, detail[:400], int(time.time()), ip, model),
            )

    def submit(self, sub_id: str, hwid: str, challenge_id: str, chain_b64: Sequence[str], signals: dict,
               ip: str = "", model: str = "") -> tuple[bool, str]:
        """Checks what the app sent for [challenge_id] and remembers the answer. Returns (ok, reason)."""
        try:
            nonce = self._take_challenge(challenge_id, sub_id, hwid)
            if not chain_b64:
                raise AttestError("no_attestation", "device: " + str(signals.get("error") or "")[:200])
            if not isinstance(chain_b64, (list, tuple)) or not 2 <= len(chain_b64) <= 10:
                raise AttestError("chain", "wrong length")
            try:
                chain = [base64.b64decode(c, validate=True) for c in chain_b64]
            except Exception:
                raise AttestError("chain", "not base64")
            roots, revoked = self._lists()
            if not roots:
                raise AttestError("unavailable", "Google roots are not loaded")
            rec = decide(chain, nonce, self.policy, roots, revoked)
            # The app is proven genuine by now, so what it says about the device can be believed.
            if signals.get("root"):
                raise AttestError("rooted", "app: " + str(signals.get("rootSigns") or "")[:120])
            detail = f"level={rec.security_level} v={rec.packages[0][1]}"
            self._store(sub_id, hwid, True, "ok", detail, ip, model)
            log.info("attest ok sub=%s hwid=%s %s", sub_id, hwid[:12], detail)
            return True, "ok"
        except AttestError as e:
            # A broken challenge or our own outage says nothing about the device: let it try again.
            if e.reason not in ("challenge", "unavailable"):
                self._store(sub_id, hwid, False, e.reason, e.detail, ip, model)
            log.info("attest refused sub=%s hwid=%s %s", sub_id, hwid[:12], e)
            return False, e.reason
        except Exception:
            log.exception("attest crashed sub=%s", sub_id)
            return False, "unavailable"


def cert_digest(hex_or_colon: str) -> bytes:
    """'0C:0C:56…' or '0c0c56…' -> 32 bytes."""
    raw = bytes.fromhex(hex_or_colon.replace(":", "").strip())
    if len(raw) != 32:
        raise ValueError("a SHA-256 digest is 32 bytes")
    return raw


def spki_sha256(cert: x509.Certificate) -> str:
    return hashlib.sha256(_spki(cert)).hexdigest()
