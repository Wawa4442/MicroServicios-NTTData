package tacos.rating;

import lombok.Value;

/**
 * How a taco scored (TC-22).
 *
 * <p>{@code average} is the real, unrounded average as the database computed it
 * and {@code votes} the number of customers behind it. The published average is
 * derived from these by the service, so the value the ranking sorted on and the
 * value shown to the customer can never drift apart.
 */
@Value
public class RatingAggregate {

  private final String tacoId;
  private final int votes;
  private final double average;

}
