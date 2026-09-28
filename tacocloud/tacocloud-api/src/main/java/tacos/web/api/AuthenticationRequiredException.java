package tacos.web.api;

/**
 * An endpoint that can only act on the authenticated caller's own data was
 * reached without a principal.
 *
 * <p>This is a 401 and not a 403 on purpose. A 403 would say "this resource
 * exists, you just may not have it", which is exactly the hint a stranger
 * needs when probing somebody else's order history.
 */
public class AuthenticationRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AuthenticationRequiredException(String message) {
    super(message);
  }

}
