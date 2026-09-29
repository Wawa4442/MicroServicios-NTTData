package tacos.idempotency;

/**
 * Lifecycle of an idempotency record.
 */
public enum IdempotencyStatus {
  PENDING,
  COMPLETED,
  FAILED
}
