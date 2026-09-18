package tacos.catalog;

/**
 * Raised when a stock adjustment would drive {@code stockOnHand} below zero.
 * The message names the ingredient and the attempted delta so the operator can
 * correct it.
 */
public class StockAdjustmentRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public StockAdjustmentRejectedException(String ingredientId, int current, int delta) {
    super("Stock adjustment of " + delta + " on ingredient " + ingredientId
        + " would leave " + (current + delta) + " (current " + current + ").");
  }

}