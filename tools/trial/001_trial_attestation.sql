-- Ghostly trial attestation (run manually, NOT on prod directly).
-- Only the trial issuance path uses these tables.
-- Paid subscriptions and /sub/<id> fetching are untouched.
--
-- challenge: one-time, TTL enforced by expires_at; used_at set atomically
--   on claim (UPDATE ... WHERE id=? AND used_at IS NULL).
-- attestation_log: every claim with neutral outcome for the client and
--   the real reason in reason_internal (server log only).
-- rate counters: soft TG/IP limits, no bans (trial refusals only).

CREATE TABLE IF NOT EXISTS trial_challenge (
    id TEXT PRIMARY KEY,
    challenge BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NULL,
    client_ip INET NULL,
    hwid_hint TEXT NULL
);

CREATE TABLE IF NOT EXISTS trial_attestation_log (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    challenge_id TEXT NULL REFERENCES trial_challenge(id) ON DELETE SET NULL,
    ok BOOLEAN NOT NULL,
    reason_internal TEXT NOT NULL,
    package_name TEXT NULL,
    app_version TEXT NULL,
    attestation_level INT NULL,
    keymaster_level INT NULL,
    boot_state INT NULL,
    device_locked BOOLEAN NULL,
    telegram_user_id BIGINT NULL,
    client_ip INET NULL,
    hwid_hint TEXT NULL
);

CREATE TABLE IF NOT EXISTS trial_claim (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    telegram_user_id BIGINT NULL,
    client_ip INET NULL,
    sub_id TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS trial_challenge_exp_idx ON trial_challenge(expires_at);
CREATE INDEX IF NOT EXISTS trial_log_at_idx ON trial_attestation_log(at);
CREATE INDEX IF NOT EXISTS trial_claim_tg_idx ON trial_claim(telegram_user_id);
CREATE INDEX IF NOT EXISTS trial_claim_ip_at_idx ON trial_claim(client_ip, at);
