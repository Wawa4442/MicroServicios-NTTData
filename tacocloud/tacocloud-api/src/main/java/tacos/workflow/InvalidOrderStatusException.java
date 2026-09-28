package tacos.workflow;

/**
 * A status request the server cannot accept as written (TC-25): unknown
 * status name, empty reason where one is required, or a reason that is too
 * long to be a useful audit note. Mapped to 400 {@code invalid_status}.
 */
public class InvalidOrderStatusException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidOrderStatusException(String message) {
    super(message);
  }
}
