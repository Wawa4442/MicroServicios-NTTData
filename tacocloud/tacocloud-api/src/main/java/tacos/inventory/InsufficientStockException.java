package tacos.inventory;

/**
 * Raised when a reservation cannot be fulfilled: some ingredient has less
 * stock than the order demands. The atomic debit guarantees the stock is never
 * taken below zero; the compensation of the other ingredients releases exactly
 * what was already reserved.
 */
public class InsufficientStockException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String ingredientId;

  public InsufficientStockException(String ingredientId, int requested) {
    super("Insufficient stock for ingredient " + ingredientId
        + " (requested " + requested + ").");
    this.ingredientId = ingredientId;
  }

  public String getIngredientId() {
    return ingredientId;
  }

}