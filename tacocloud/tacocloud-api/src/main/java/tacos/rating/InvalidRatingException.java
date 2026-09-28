package tacos.rating;

/**
 * A score outside the configured scale, or an unusable ranking request. Reported
 * as 400 with the code {@code invalid_rating}: the request is well formed
 * HTTP, the number in it simply is not a score this system accepts.
 */
public class InvalidRatingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidRatingException(String message) {
    super(message);
  }

}
