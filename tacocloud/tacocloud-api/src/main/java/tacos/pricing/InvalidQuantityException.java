package tacos.pricing;

/**
 * Raised when a line quantity is outside the accepted range (below 1 or above
 * the configured maximum). The order is never priced nor saved.
 */
public class InvalidQuantityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidQuantityException(String message) {
    super(message);
  }

}