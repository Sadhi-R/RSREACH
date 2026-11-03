package com.rsreach.contacts;

/**
 * Signals that a concurrent writer won the idempotency/loser unique race.
 * The calling transaction must roll back; the facade reloads the committed merge.
 */
final class MergeIdempotencyRaceException extends RuntimeException {

    private final String idempotencyKey;
    private final String requestHash;

    MergeIdempotencyRaceException(String idempotencyKey, String requestHash, Throwable cause) {
        super("Merge idempotency race", cause);
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    String requestHash() {
        return requestHash;
    }
}
