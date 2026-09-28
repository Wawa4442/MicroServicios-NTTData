package tacos.paging;

/**
 * Raised when a client asks for a page window the endpoint is not willing to
 * serve: a non-positive or oversized {@code size}, a negative {@code page}, or
 * an offset so deep that the database would have to skip it entirely.
 */
public class InvalidPageBoundsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidPageBoundsException(String message) {
    super(message);
  }

}
