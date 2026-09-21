package tacos.rules;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.Ingredient.Type;

/**
 * A taco must have exactly one base (currently WRAP; a BOWL base would be a
 * second accepted type in the future). Zero or multiple bases are physically
 * unsound and rejected with a stable code.
 */
@Component
public class SingleBaseRule implements TacoRule {

  @Override
  public List<RuleViolation> check(List<Ingredient> ingredients) {
    long wraps = ingredients.stream()
        .filter(i -> i != null && i.getType() == Type.WRAP)
        .count();
    if (wraps == 1) {
      return Collections.emptyList();
    }
    if (wraps == 0) {
      return Collections.singletonList(
          RuleViolation.of("SINGLE_BASE", "A taco needs exactly one base (wrap or bowl)."));
    }
    return Collections.singletonList(
        RuleViolation.of("SINGLE_BASE", "A taco can only have one base, found " + wraps + "."));
  }

}