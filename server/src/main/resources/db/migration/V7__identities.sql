-- External OIDC/OAuth subject bindings and immutable guest-merge aliases.
--
-- An identity binds (issuer, subject) to a first-party owner_id. Lookups and
-- account promotion match strictly on (issuer, subject), never on raw email,
-- blocking pre-account takeover (RFC 9700).
--
-- When a guest account merges into an existing owner, owner_aliases maps the
-- retired guest owner_id to the canonical owner_id so StoreKit appAccountToken
-- and Play obfuscatedAccountId renewal webhooks continue to resolve.
--
-- Portable across H2 (tests) and Postgres.

CREATE TABLE identities (
    issuer         TEXT    NOT NULL,
    subject        TEXT    NOT NULL,
    owner_id       TEXT    NOT NULL,
    email          TEXT,
    email_verified BOOLEAN NOT NULL,
    created_at     BIGINT  NOT NULL,
    PRIMARY KEY (issuer, subject)
);

CREATE INDEX identities_owner_idx ON identities (owner_id);

CREATE TABLE owner_aliases (
    alias_owner_id     TEXT   PRIMARY KEY,
    canonical_owner_id TEXT   NOT NULL,
    created_at         BIGINT NOT NULL
);

CREATE INDEX owner_aliases_canonical_idx ON owner_aliases (canonical_owner_id);
