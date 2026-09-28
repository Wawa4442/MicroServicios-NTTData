package tacos.workflow;

/**
 * A status move that the lifecycle matrix forbids (TC-25).
 *
 * <p>For example {@code CREATED -> DELIVERED}. Mapped to 409 with the stable
 * code {@code invalid_status_transition} so clients can tell "bad order of
 * steps" apart from "you are not allowed" (403) and "bad payload" (400).
 */
public class OrderStatusTransitionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrderStatusTransitionException(String message) {
    super(message);
  }
}
