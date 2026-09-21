package tacos.rules;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;

/**
 * Fun rule: a Ghost Pepper taco demands a drink. The rule is inert when no
 * ghost pepper ingredient or no drink id is configured, so environments that
 * do not want a "Physics" reality check simply leave the ids unset.
 */
@Component
public class GhostPepperNeedsDrinkRule implements TacoRule {

  private final TacoPhysicsProperties properties;

  public GhostPepperNeedsDrinkRule(TacoPhysicsProperties properties) {
    this.properties = properties;
  }

  @Override
  public List<RuleViolation> check(List<Ingredient> ingredients) {
    if (properties.getGhostPepperIngredientId() == null
        || properties.getGhostPepperIngredientId().trim().isEmpty()) {
      return Collections.emptyList();
    }
    boolean hasGhost = ingredients.stream().anyMatch(
        i -> i != null && i.getId() != null
            && i.getId().equals(properties.getGhostPepperIngredientId()));
    if (!hasGhost) {
      return Collections.emptyList();
    }
    boolean hasDrink = ingredients.stream().anyMatch(
        i -> i != null && i.getId() != null
            && properties.getDrinkIngredientIds().contains(i.getId()));
    if (hasDrink) {
      return Collections.emptyList();
    }
    return Collections.singletonList(RuleViolation.of("GHOST_PEPPER_NEEDS_DRINK",
        "A Ghost Pepper taco demands a drink to tame the heat."));
  }

}