package tacos.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Where "today" starts (TC-20). The zone is configuration because a taco shop's
 * day is not UTC: a recommendation that flips at 00:00 UTC flips in the middle
 * of the afternoon for a customer in another continent.
 */
@Component
@ConfigurationProperties(prefix = "tacos.recommendation")
@Data
public class TacoRecommendationProperties {

  /** Zone id used to resolve the current business date. */
  private String zone = "UTC";

}
