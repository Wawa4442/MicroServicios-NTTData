package tacos.reorder;

/**
 * A reorder arrived without a payment method (TC-24). Reported as 400 rather
 * than being quietly satisfied with the original token: a token that was valid
 * months ago is not something to charge an account with on the customer's
 * behalf without being told which one is being used.
 */
public class ReorderPaymentMethodRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ReorderPaymentMethodRequiredException() {
    super("Reordering requires an explicit paymentMethodId; the original order's payment "
        + "reference is not reused.");
  }

}
