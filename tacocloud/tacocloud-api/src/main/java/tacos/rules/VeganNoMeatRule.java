package tacos.rules;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;

/**
 * Fun rule: vegans do not accept carnitas. If a design mixes a VEGAN-labelled
 * ingredient with another that carries the MEAT allergen, the label is a
 * contradiction and the design is rejected. Disabled via
 * {@code tacos.physics.vegan-no-meat-enabled=false} when the academy wants to
 * allow the mix.
 */
@Component
public class VeganNoMeatRule implements TacoRule {

  private final TacoPhysicsProperties properties;

  public VeganNoMeatRule(TacoPhysicsProperties properties) {
    this.properties = properties;
  }

  @Override
  public List<RuleViolation> check(List<Ingredient> ingredients) {
    if (!properties.isVeganNoMeatEnabled()) {
      return Collections.emptyList();
    }
    boolean hasVeganLabel = ingredients.stream().anyMatch(
        i -> i != null && i.getDietaryTags() != null
            && i.getDietaryTags().contains(DietaryTag.VEGAN));
    boolean hasMeatAllergen = ingredients.stream().anyMatch(
        i -> i != null && i.getAllergens() != null
            && i.getAllergens().contains(Allergen.MEAT));
    if (hasVeganLabel && hasMeatAllergen) {
      return Collections.singletonList(RuleViolation.of("VEGAN_NO_MEAT",
          "A vegan-labelled ingredient cannot share a taco with animal protein."));
    }
    return Collections.emptyList();
  }

}