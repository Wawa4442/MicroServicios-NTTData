package tacos.web.api;

/**
 * A taco id that does not exist in the catalog. Kept separate from
 * {@code OrderNotFoundException} so a client can tell "no such taco" from "no
 * such order" without parsing a message.
 */
public class TacoNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TacoNotFoundException(String tacoId) {
    super("There is no taco with id " + tacoId + ".");
  }

}
