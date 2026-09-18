package tacos.web.api;

/**
 * Raised when an order create/replace references a payment method that does
 * not exist or is not usable by the caller.
 */
public class UnknownPaymentMethodException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownPaymentMethodException(String paymentMethodId) {
    super("Unknown payment method id: " + paymentMethodId);
  }

}