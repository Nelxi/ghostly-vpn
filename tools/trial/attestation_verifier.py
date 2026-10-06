#!/usr/bin/env python3
"""Ghostly trial Key Attestation verifier (server-side reference).

Runs ONLY on the trial issuance path. Paid subscriptions and link
subscriptions never touch this code.

Checks, in order:
  1. challenge exists, not expired, not used before (caller enforces
     one-time use atomically, e.g. DELETE ... WHERE id=? AND used=0);
  2. X.509 chain validates up to a pinned Google attestation root;
  3. leaf serial is not on the Google revocation status list;
  4. attestation extension (OID 1.3.6.1.4.1.11129.2.1.17) parses;
  5. attestationChallenge == issued challenge;
  6. attestationApplicationId package == app.ghostly.vpn and one of
     signature_digests == SHA-256(release signing cert);
  7. attestationSecurityLevel and keymasterSecurityLevel are hardware
     (TrustedEnvironment=1/StrongBox=2); Software=0 is rejected;
  8. rooted/unlocked devices (verifiedBootState != Verified or
     deviceLocked=False) are rejected for trials.

On ANY failure returns (False, <internal_reason>). The HTTP layer must
answer with the single neutral string and log <internal_reason> only.

Google roots: download from
  https://developer.android.com/privacy-and-security/security-key-attestation
Revocation list:
  https://android.googleapis.com/attestation/status
Both should be cached locally and refreshed daily; pins live in config,
not in code.

Requires: cryptography>=42 (pip install cryptography).
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Iterable, Sequence

from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from dataclasses import dataclass
import hashlib

ATTEST_OID = "1.3.6.1.4.1.11129.2.1.17"

EXPECTED_PACKAGE = "app.ghostly.vpn"
# KeyMint security levels: 0=Software, 1=TrustedEnvironment, 2=StrongBox.
HW_LEVELS = {1, 2}
# VerifiedBootState: 0=Verified, 1=SelfSigned, 2=Unverified, 3=Failed.
VERIFIED_BOOT_VERIFIED = 0

NEUTRAL_ERROR = "trial_unavailable"


def pem_to_certs(pem_chain: Sequence[str]) -> list[x509.Certificate]:
    certs = [x509.load_pem_x509_certificate(p.encode()) for p in pem_chain]
    if not certs:
        raise ValueError("empty chain")
    return certs


def verify_chain(leaf_to_root: Sequence[x509.Certificate],
                 trusted_roots: Sequence[x509.Certificate]) -> None:
    """Each cert must be signed by the next; chain must end in a pinned root."""
    roots = {c.subject.rfc4514_string(): c for c in trusted_roots}
    for child, parent in zip(leaf_to_root, list(leaf_to_root[1:])):
        _verify_signed(child, parent.public_key())
        if child.issuer != parent.subject:
            raise ValueError("issuer/subject mismatch")
        now = datetime.now(timezone.utc)
        if not (parent.not_valid_before_utc <= now <= parent.not_valid_after_utc):
            raise ValueError("parent cert not valid now")
    root = leaf_to_root[-1]
    pinned = roots.get(root.subject.rfc4514_string())
    if pinned is None:
        raise ValueError("chain does not end in a pinned Google root")
    if root.fingerprint(hashes.SHA256()) != pinned.fingerprint(hashes.SHA256()):
        raise ValueError("root fingerprint mismatch")
    _verify_signed(root, root.public_key())


def _verify_signed(child: x509.Certificate, pub) -> None:
    if isinstance(pub, ec.EllipticCurvePublicKey):
        pub.verify(child.signature, child.tbs_certificate_bytes,
                   ec.ECDSA(child.signature_hash_algorithm))
    elif isinstance(pub, rsa.RSAPublicKey):
        pub.verify(child.signature, child.tbs_certificate_bytes,
                   padding.PKCS1v15(), child.signature_hash_algorithm)
    else:  # pragma: no cover - unexpected key type
        raise ValueError("unsupported parent key type")


@dataclass
class AttestationInfo:
    challenge: bytes
    package_name: str
    version_code: int
    signature_digests: list[bytes]
    attestation_level: int
    keymaster_level: int
    verified_boot_state: int
    device_locked: bool


def _read_tlv(data: bytes, pos: int):
    tag = data[pos]; pos += 1
    length = data[pos]; pos += 1
    if length & 0x80:
        n = length & 0x7F
        length = int.from_bytes(data[pos:pos + n], "big"); pos += n
    return tag, data[pos:pos + length], pos + length


def parse_attestation_record(ext_bytes: bytes) -> AttestationInfo:
    """Parse the Key Attestation extension body.

    Top-level SEQUENCE: INTEGER version, INTEGER attestationLevel,
    INTEGER keymasterVersion, INTEGER keymasterLevel, OCTET STRING
    challenge, then enforced lists. Package/digests and RootOfTrust are
    found by scanning nested sequences (see _try_parse_app_id /
    _try_parse_root), so minor schema additions do not break parsing.
    """
    tag, seq, _ = _read_tlv(ext_bytes, 0)
    if tag != 0x30:
        raise ValueError("attestation ext not a SEQUENCE")
    ints: list[int] = []
    octets: list[bytes] = []
    q = 0
    while q < len(seq):
        t, v, q = _read_tlv(seq, q)
        if t == 0x02:
            ints.append(int.from_bytes(v, "big", signed=True))
        elif t == 0x04:
            octets.append(v)
    if len(ints) < 4 or len(octets) < 1:
        raise ValueError("attestation record truncated")
    _ver, att_level, _km_ver, km_level = ints[0], ints[1], ints[2], ints[3]
    found = _find_app_id(seq)
    if found is None:
        raise ValueError("attestationApplicationId missing")
    package, version_code, digests = found
    boot_state, locked = _find_root_of_trust(seq)
    return AttestationInfo(
        challenge=octets[0],
        package_name=package,
        version_code=version_code,
        signature_digests=digests,
        attestation_level=att_level,
        keymaster_level=km_level,
        verified_boot_state=boot_state,
        device_locked=locked,
    )


def _find_app_id(seq: bytes):
    """Locate inner SEQUENCE parsing as AttestationApplicationId.

    Real records wrap it as [710] EXPLICIT AuthorizationList, which itself
    holds context-tagged fields. So: collect every constructed payload
    recursively AND try parsing each raw TLV value directly.
    """
    stack = [seq]
    while stack:
        cur = stack.pop()
        pos = 0
        while pos + 2 <= len(cur):
            try:
                t, v, pos = _read_tlv(cur, pos)
            except (IndexError, ValueError):
                break
            parsed = _try_parse_app_id(v)
            if parsed is not None:
                return parsed
            if t in (0x30, 0x31) or (t & 0xC0) == 0x80 or t in (0x04,):
                # constructed / context / octet containers may nest it
                if len(v) >= 2:
                    stack.append(v)
    return None


def _try_parse_app_id(v: bytes):
    # AttestationApplicationId ::= SEQUENCE { SET OF PackageInfo, SET OF digests }
    # PackageInfo ::= SEQUENCE { OCTET STRING name, INTEGER version }.
    # v may be the full TLV (tag+len+body) or the bare body; accept both.
    try:
        body = v
        t0, inner0, _ = _read_tlv(v, 0)
        if t0 == 0x30:
            body = inner0
        p = 0
        t1, s1, p = _read_tlv(body, p)
        t2, s2, p = _read_tlv(body, p)
        if t1 != 0x31 or t2 != 0x31:
            return None
        _, pkg_seq, _ = _read_tlv(s1, 0)
        r = 0
        tn, name_b, r = _read_tlv(pkg_seq, r)
        tv, ver_b, r = _read_tlv(pkg_seq, r)
        if tn != 0x04 or tv != 0x02:
            return None
        digests: list[bytes] = []
        q = 0
        while q + 2 <= len(s2):
            td, d, q = _read_tlv(s2, q)
            if td != 0x04:
                return None
            digests.append(d)
        if not digests:
            return None
        return name_b.decode("utf-8", "strict"), int.from_bytes(ver_b, "big"), digests
    except Exception:
        return None


def _find_root_of_trust(seq: bytes) -> tuple[int, bool]:
    # RootOfTrust ::= SEQUENCE { OCTET STRING key, BOOLEAN locked, ENUM state, ... }.
    stack = [seq]
    while stack:
        cur = stack.pop()
        pos = 0
        while pos + 2 <= len(cur):
            try:
                t, v, pos = _read_tlv(cur, pos)
            except (IndexError, ValueError):
                break
            parsed = _try_parse_root(v)
            if parsed is not None:
                return parsed
            if t in (0x30, 0x31) or (t & 0xC0) == 0x80 or t == 0x04:
                if len(v) >= 2:
                    stack.append(v)
    return 2, False  # absent = treat as unverified


def _try_parse_root(v: bytes):
    # RootOfTrust ::= SEQUENCE { OCTET STRING key, BOOLEAN locked, ENUM state, ... }.
    # v may be the full TLV or the bare body; accept both.
    try:
        body = v
        t0, inner0, _ = _read_tlv(v, 0)
        if t0 == 0x30:
            body = inner0
        p = 0
        tk, key_b, p = _read_tlv(body, p)
        tl, lock_b, p = _read_tlv(body, p)
        ts, st_b, p = _read_tlv(body, p)
        if tk != 0x04 or tl != 0x01 or ts != 0x0A:
            return None
        if len(key_b) not in (0, 32):
            return None
        return int.from_bytes(st_b, "big"), lock_b == b"\xff"
    except Exception:
        return None


def check_not_revoked(leaf_serial_hex: str, revoked_serials: Iterable[str]) -> None:
    bad = {s.lower().lstrip("0") or "0" for s in revoked_serials}
    if (leaf_serial_hex.lower().lstrip("0") or "0") in bad:
        raise ValueError("leaf serial revoked")


def leaf_attestation_info(leaf: x509.Certificate):
    from cryptography.x509.oid import ObjectIdentifier
    ext = leaf.extensions.get_extension_for_oid(ObjectIdentifier(ATTEST_OID))
    return parse_attestation_record(ext.value.value)


def decide(chain_pem: Sequence[str],
           expected_challenge: bytes,
           release_cert_digests: Sequence[bytes],
           trusted_roots_pem: Sequence[str],
           revoked_serials: Iterable[str]) -> tuple[bool, str]:
    """Full policy decision. Returns (ok, internal_reason for server log)."""
    try:
        certs = pem_to_certs(chain_pem)
        roots = [x509.load_pem_x509_certificate(p.encode()) for p in trusted_roots_pem]
        verify_chain(certs, roots)
    except Exception as e:
        return False, f"chain: {e}"
    leaf = certs[0]
    try:
        check_not_revoked(f"{leaf.serial_number:x}", revoked_serials)
    except Exception as e:
        return False, f"revoked: {e}"
    try:
        info = leaf_attestation_info(leaf)
    except Exception as e:
        return False, f"parse: {e}"
    if info.challenge != expected_challenge:
        return False, "challenge mismatch"
    if info.package_name != EXPECTED_PACKAGE:
        return False, f"package {info.package_name!r}"
    want = {bytes(d) for d in release_cert_digests}
    if not (set(map(bytes, info.signature_digests)) & want):
        got = [hashlib.sha256(bytes(d)).hexdigest()[:12]
               for d in info.signature_digests]
        return False, f"signer mismatch digests={got}"
    if info.attestation_level not in HW_LEVELS or info.keymaster_level not in HW_LEVELS:
        return False, (f"software level att={info.attestation_level} "
                       f"km={info.keymaster_level}")
    if info.verified_boot_state != VERIFIED_BOOT_VERIFIED or not info.device_locked:
        return False, (f"root/unlocked boot={info.verified_boot_state} "
                       f"locked={info.device_locked}")
    return True, "ok"


