package tacos.outbox;

/**
 * Local delivery state of an event (TC-29).
 *
 * <p>{@code NEW} waits for the relay, {@code PUBLISHING} is claimed by one
 * relay instance, {@code PUBLISHED} is done, {@code FAILED} exhausted its
 * retries and needs an operator. The broker may still redeliver, so the
 * consumer (TC-30) deduplicates anyway: at-least-once out, idempotent in.
 */
public enum OutboxStatus {
  NEW,
  PUBLISHING,
  PUBLISHED,
  FAILED
}
