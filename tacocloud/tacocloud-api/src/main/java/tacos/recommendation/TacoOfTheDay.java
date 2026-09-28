package tacos.recommendation;

import java.time.LocalDate;

import lombok.Value;

/**
 * The daily recommendation and the reason behind it (TC-20).
 *
 * <p>The reason is plain text on purpose. It states the facts that are actually
 * known — the date, the taco, and that the choice is the same for everybody —
 * and nothing else. It never invents a sales figure, a review count or a
 * discount that the system did not compute.
 */
@Value
public class TacoOfTheDay {

  private final String tacoId;
  private final String name;
  private final LocalDate date;
  private final String reason;

}
