-- SEC-PILOT-CREDENTIALS-HARDENING (ADR-006): staff session invalidation and forced password change.
-- token_version: monotonic per-user counter carried in the staff JWT claim "tv" and checked against
-- the database on every request; any credential, status or privilege change increments it, which
-- invalidates every token issued before. must_change_password: set by an ADMIN reset or break-glass
-- recovery; the session is then restricted to the password-change flow. Existing rows (humans and
-- non-login service accounts) take the defaults: no data is rewritten.

ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
