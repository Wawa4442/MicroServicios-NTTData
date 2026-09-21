package tacos.rules;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;

/**
 * Only ingredients currently available for sale can enter a taco. The rule
 * uses the already-resolved entities, so it never issues an extra catalog or
 * inventory query (TC-18: validation happens before pricing and reservation).
 */
@Component
public class AvailableIngredientsRule implements TacoRule {

  @Override
  public List<RuleViolation> check(List<Ingredient> ingredients) {
    List<RuleViolation> violations = new ArrayList<>();
    for (Ingredient ingredient : ingredients) {
      if (ingredient != null && !ingredient.isAvailable()) {
        violations.add(RuleViolation.of("UNAVAILABLE_INGREDIENT",
            "Ingredient " + ingredient.getId() + " is not available for sale."));
      }
    }
    return violations;
  }

}