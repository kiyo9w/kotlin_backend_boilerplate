package com.example.server.core

/**
 * Thrown when a terminal or generated-result write names a claim that is no
 * longer current.
 *
 * A claim is fenced by its attempt number. Once the lease expires and another
 * worker reclaims the job, the attempt moves on and the previous worker's
 * writes must not land: without the fence, a stale attempt could commit
 * results and a terminal status after recovery, overwriting the live attempt's
 * work.
 */
class StaleClaimException(message: String) : RuntimeException(message)
