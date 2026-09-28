package tacos.rating;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Rating rules as configuration (TC-22). The scale, the average precision and
 * the minimum-vote floor are business decisions that a product owner may want
 * to change without a redeploy, so they are not literals in the service.
 */
@Component
@ConfigurationProperties(prefix = "tacos.ratings")
@Data
public class RatingProperties {

  private int minScore = 1;

  private int maxScore = 5;

  /** Decimal places of the published average. */
  private int averageScale = 2;

  /**
   * Votes a taco needs before it may appear in the ranking. Without this floor
   * a single five-star vote would top the chart, which is mathematically true
   * and completely useless as a recommendation.
   */
  private int minVotes = 2;

  private int defaultTopLimit = 10;

  private int maxTopLimit = 50;

}
