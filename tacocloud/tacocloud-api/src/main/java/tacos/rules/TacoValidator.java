package tacos.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import tacos.Ingredient;

/**
 * Central Taco Physics validator (TC-18). Executes every injected rule and
 * aggregates all violations in one pass; the order of rules never changes the
 * result because there is no fail-fast.
 */
@Service
public class TacoValidator {

  private final List<TacoRule> rules;

  public TacoValidator(List<TacoRule> rules) {
    this.rules = rules;
  }

  public List<RuleViolation> violations(List<Ingredient> ingredients) {
    List<Ingredient> safe = ingredients == null
        ? Collections.emptyList() : ingredients;
    if (rules == null) {
      return Collections.emptyList();
    }
    List<RuleViolation> all = new ArrayList<>();
    for (TacoRule rule : rules) {
      all.addAll(rule.check(safe));
    }
    return all;
  }

  public void validateOrThrow(List<Ingredient> ingredients) {
    List<RuleViolation> violations = violations(ingredients);
    if (!violations.isEmpty()) {
      throw new TacoDesignInvalidException(violations);
    }
  }

}