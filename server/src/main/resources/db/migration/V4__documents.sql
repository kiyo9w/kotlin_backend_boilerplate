-- Versioned owner-scoped documents (DocumentStore). The composite primary key
-- (owner_id, document_id) is the create gate: two concurrent creates cannot
-- both land, which is what lets the store answer a create race with a conflict
-- instead of a duplicate-key error.
--
-- version is a monotonic integer owned by the store and the compare-and-set
-- token: an update matches only when the writer's base version is still
-- current, so exactly one of two writers holding the same version lands.
-- payload is the product's JSON, opaque here. updated_at is epoch millis.
--
-- Portable across H2 (tests) and Postgres.

CREATE TABLE documents (
    owner_id       TEXT NOT NULL,
    document_id    TEXT NOT NULL,
    schema_version INT NOT NULL,
    version        BIGINT NOT NULL,
    payload        TEXT NOT NULL,
    updated_at     BIGINT NOT NULL,
    PRIMARY KEY (owner_id, document_id)
);
