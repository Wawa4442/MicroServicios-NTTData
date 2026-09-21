package tacos.rules;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;

/**
 * A taco must have between {@code min} and {@code max} ingredients (both
 * inclusive). Bounds are configuration, not magic constants.
 */
@Component
public class IngredientCountRule implements TacoRule {

  private final TacoPhysicsProperties properties;

  public IngredientCountRule(TacoPhysicsProperties properties) {
    this.properties = properties;
  }

  @Override
  public List<RuleViolation> check(List<Ingredient> ingredients) {
    int count = ingredients == null ? 0 : ingredients.size();
    if (count < properties.getMinIngredients()) {
      return Collections.singletonList(RuleViolation.of("TOO_FEW_INGREDIENTS",
          "A taco needs at least " + properties.getMinIngredients() + " ingredients."));
    }
    if (count > properties.getMaxIngredients()) {
      return Collections.singletonList(RuleViolation.of("TOO_MANY_INGREDIENTS",
          "A taco can have at most " + properties.getMaxIngredients() + " ingredients."));
    }
    return Collections.emptyList();
  }

}