package tacos.web.api;

/**
 * Raised when an email order cannot be resolved because the sender is not a
 * known user or the user has no payment method on file.
 */
public class EmailOrderConversionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailOrderConversionException(String reason) {
    super(reason);
  }

}