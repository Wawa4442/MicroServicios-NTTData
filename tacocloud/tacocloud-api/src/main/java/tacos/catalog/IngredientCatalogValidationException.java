package tacos.catalog;

/**
 * Raised when an ingredient update would violate a catalog invariant
 * (negative price, negative stock, or "available" without stock).
 */
public class IngredientCatalogValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IngredientCatalogValidationException(String message) {
    super(message);
  }

}