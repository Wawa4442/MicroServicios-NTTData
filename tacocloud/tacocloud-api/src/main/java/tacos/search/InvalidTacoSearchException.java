package tacos.search;

/**
 * A rejected catalog query: an unknown sort field or direction, a diet/
 * allergen/spice value that is not part of the domain vocabulary, or a
 * free-text term longer than the configured cap. Reported as 400 with the code
 * {@code invalid_search} so a client can tell "you asked for something I do
 * not have" apart from "the query ran and found nothing".
 */
public class InvalidTacoSearchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidTacoSearchException(String message) {
    super(message);
  }

}
