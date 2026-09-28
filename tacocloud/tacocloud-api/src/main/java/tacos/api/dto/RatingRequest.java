package tacos.api.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import lombok.Data;

/**
 * Input of {@code PUT /api/tacos/{id}/rating} (TC-22).
 *
 * <p>Only the score. There is no {@code userId} field, and adding one later
 * would be the classic "vote on behalf of somebody else" bug: the ratings
 * contract says the caller rates as themselves, so identity is taken from the
 * session and never from the payload.
 *
 * <p>The bounds mirror the defaults of {@code tacos.ratings}. The service
 * re-checks them against the effective configuration, because a body annotation
 * cannot see it — this is a cheap first line of defence, not the only one.
 */
@Data
public class RatingRequest {

  @NotNull(message = "score is required")
  @Min(value = 1, message = "score must be at least 1")
  @Max(value = 5, message = "score must not exceed 5")
  private Integer score;

}
