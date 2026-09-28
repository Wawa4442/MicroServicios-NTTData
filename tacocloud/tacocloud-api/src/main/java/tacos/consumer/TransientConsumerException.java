package tacos.consumer;

/**
 * A transient failure the broker should redeliver (TC-30): optimistic-lock
 * collision, timeout, momentary store outage. The message must NOT be
 * acknowledged before the effect is durable; the broker retry plus the
 * {@code eventId} dedup turn the redelivery into "same effect once".
 */
public class TransientConsumerException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TransientConsumerException(String message, Throwable cause) {
    super(message, cause);
  }
}
