package tacos.rules;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import tacos.Ingredient;

/**
 * An ingredient can appear only once in a taco. Duplicates would let a client
 * inflate flavor and, combined with pricing, cheaply copy a protein twice.
 */
@Component
public class NoDuplicateIngredientRule implements TacoRule {

  @Override
  public List<RuleViolation> check(List<Ingredient> ingredients) {
    Set<String> seen = new HashSet<>();
    for (Ingredient ingredient : ingredients) {
      if (ingredient != null && ingredient.getId() != null && !seen.add(ingredient.getId())) {
        return Collections.singletonList(RuleViolation.of(
            "DUPLICATE_INGREDIENT",
            "Ingredient " + ingredient.getId() + " appears more than once."));
      }
    }
    return Collections.emptyList();
  }

}