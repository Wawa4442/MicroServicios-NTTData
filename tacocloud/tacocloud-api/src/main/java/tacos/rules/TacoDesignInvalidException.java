package tacos.rules;

import java.util.List;

/**
 * Raised when a taco design fails one or more rules before it is priced,
 * quoted, persisted or reserved. Carries the stable violations so the HTTP
 * mapping can expose them as Problem Details (422 {@code taco_design_invalid}).
 */
public class TacoDesignInvalidException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final List<RuleViolation> violations;

  public TacoDesignInvalidException(List<RuleViolation> violations) {
    super(violations.isEmpty() ? "Invalid taco design."
        : violations.get(0).getMessage());
    this.violations = violations;
  }

  public List<RuleViolation> getViolations() {
    return violations;
  }

}