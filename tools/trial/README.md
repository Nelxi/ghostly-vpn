# Trial attestation — server integration guide (reference)

> Backend repo is closed; this folder is a drop-in reference + migrations.
> Only the **trial issuance path** uses it. Paid subscriptions and
> `/sub/<id>` fetching are never touched.

## Files

| File | What |
|---|---|
| `attestation_verifier.py` | Chain/root/revocation/challenge/package/signer/level/boot check. Returns `(ok, internal_reason)`. |
| `test_attestation_verifier.py` | 10 tests: valid, wrong digest, wrong challenge, software level (both), rooted, unlocked, revoked, wrong package, bad root. |
| `001_trial_attestation.sql` | `trial_challenge`, `trial_attestation_log`, `trial_claim` + indexes. Run manually, not on prod directly. |
| `backup-trial.sh` | Read-only backup per host (pg_dump + configs). Key `C:/Users/kille/Videos/krazeshit_new.ppk`, port `2149`. |

## Endpoints to add on backend

```text
POST /trial/challenge  -> { "id": "<uuid>", "challenge": "<b64 32B>", "expiresIn": 300 }
POST /trial/claim      -> 200 { "ok": true, "subscriptionUrl": "https://..." }
                          200/403 { "ok": false, "error": "trial_unavailable" }  # ALWAYS neutral
```

Claim body: `{ challengeId, chainPem[], packageName, appVersion, hwidHint? }`.

## Claim pseudocode

```python
row = db.one("SELECT challenge, expires_at FROM trial_challenge WHERE id=?", cid)
if not row or row.expires_at < now():
    log(...); return neutral()
# one-time: atomic consume
n = db.exec("UPDATE trial_challenge SET used_at=now() WHERE id=? AND used_at IS NULL", cid)
if n == 0:
    log("challenge reuse"); return neutral()
if app_version < MIN_VERSION or telegram_claimed(tg_id) or ip_over_limit(ip):
    log(...); return neutral()
ok, reason = decide(chain_pem, row.challenge, RELEASE_DIGESTS, GOOGLE_ROOTS, REVOKED)
log(ok, reason, ...)  # reason_internal only, never to client
if not ok:
    return neutral()
sub = issue_trial(tg_id)  # existing logic
return {"ok": True, "subscriptionUrl": sub}
```

Config (not code): `GOOGLE_ROOTS_PEM` (refresh from Android docs),
`REVOCATION_LIST` (`https://android.googleapis.com/attestation/status`, cache daily),
`RELEASE_CERT_SHA256` (from `apksigner verify --print-certs` of the release APK),
`MIN_APP_VERSION = 0.3.26/61`, soft limits `TG: 1 trial forever`,
`IP /32: 3/24h, /24: 10/24h, IPv6 /64 as unit` — over-limit = neutral refusal, no bans.

## Windows

No attestation available: do not expose trial in the desktop client.
Trial only via Telegram bot (account checks + same IP limits), or disable
trial on Windows entirely.
