package tacos.api.dto;

import java.math.BigDecimal;
import java.util.Date;

import lombok.Value;

import tacos.TacoRating;

/**
 * The caller's rating after it was recorded (TC-22).
 *
 * <p>It carries the taco's new average and vote count so a client that changed
 * its mind can correct the number on screen in the same round trip. Those two
 * are recomputed from the whole collection by an aggregation, never derived
 * from the value that was just written, so they cannot drift from what the
 * ranking will report.
 */
@Value
public class RatingResponse {

  private final String tacoId;
  private final int score;
  private final BigDecimal averageScore;
  private final int votes;
  private final Date updatedAt;

  public static RatingResponse of(TacoRating rating, BigDecimal averageScore, int votes) {
    return new RatingResponse(rating.getTacoId(), rating.getScore(), averageScore, votes,
        rating.getUpdatedAt());
  }

}
