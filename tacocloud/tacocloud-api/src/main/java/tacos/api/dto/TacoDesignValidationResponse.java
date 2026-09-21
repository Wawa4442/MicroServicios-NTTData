package tacos.api.dto;

import java.util.List;

import lombok.Value;
import tacos.classification.TacoClassification;
import tacos.rules.RuleViolation;

/**
 * Answer of {@code POST /api/tacos/validate}. Returns every violation found,
 * so clients can render all of them at once; an empty list means the design is
 * valid. The evaluation is a query, so even an invalid design is a 200.
 */
@Value
public class TacoDesignValidationResponse {

  private final boolean valid;
  private final List<RuleViolation> violations;
  private final TacoClassification classification;

  public static TacoDesignValidationResponse of(List<RuleViolation> violations,
      TacoClassification classification) {
    return new TacoDesignValidationResponse(violations.isEmpty(), violations, classification);
  }

}