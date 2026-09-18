package tacos.web.api;

/**
 * Raised when an email order references an ingredient id that does not exist
 * in the catalog.
 */
public class UnknownIngredientException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownIngredientException(String ingredientId) {
    super("Unknown ingredient id: " + ingredientId);
  }

}