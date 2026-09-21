package tacos.rules;

import lombok.Value;

/**
 * One failed rule of the Taco Physics engine (TC-18). Carries a stable code
 * for the UI and a human message. Rules return violations; they never throw a
 * generic exception by themselves.
 */
@Value
public class RuleViolation {

  private final String code;
  private final String message;

  public static RuleViolation of(String code, String message) {
    return new RuleViolation(code, message);
  }

}