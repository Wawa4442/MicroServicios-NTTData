package tacos.recommendation;

import java.time.LocalDate;

/**
 * No taco is currently recommendable (TC-20): every taco in the catalog either
 * references an ingredient that is off the menu or breaks a Taco Physics rule.
 * Reported as 404 because there is nothing to return, and an empty 200 body
 * would leave the caller guessing whether the feature is broken.
 */
public class NoTacoOfTheDayException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NoTacoOfTheDayException(LocalDate date) {
    super("No taco is available to recommend on " + date + ".");
  }

}
