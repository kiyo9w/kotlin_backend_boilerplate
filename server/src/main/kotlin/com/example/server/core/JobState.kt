package com.example.server.core

/**
 * Generic job lifecycle states. Every product uses these five; the vocabulary
 * comes from the maintainable-app job design .
 *
 * PENDING  → enqueued, waiting for a worker
 * RUNNING  → claimed, lease active
 * SUCCEEDED → terminal, result recorded
 * FAILED   → terminal, exhausted retries or unrecoverable error
 * STALE    → lease expired without a terminal write; reclaimable
 */
enum class JobState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    STALE,
}
