package tacos.classification;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import lombok.Value;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.SpiceLevel;

/**
 * Derived truth about a taco (TC-17). Classification is computed from the
 * taco's ingredients; it is never accepted from the client, so the labels
 * cannot be falsified in a POST.
 *
 * <p>Policy:
 * <ul>
 *   <li>a dietary tag is only true when <b>every</b> ingredient carries it
 *       ({@code "sin X"} requires all to comply); an ingredient tagged VEGAN
 *       is, by data convention, also VEGETARIAN;</li>
 *   <li>allergens are the <b>exact union</b> of the ingredients' allergens —
 *       they are added, never removed by majority;</li>
 *   <li>spice level is the maximum ingredient level, so it is deterministic
 *       and explainable.</li>
 * </ul>
 *
 * <p>Academic disclaimer: these labels come from catalog metadata and do not
 * replace a real cross-contamination control in a production kitchen.
 */
@Value
public class TacoClassification {

  private final Set<DietaryTag> dietaryTags;
  private final Set<Allergen> allergens;
  private final SpiceLevel spiceLevel;

  public static TacoClassification fromIngredients(List<Ingredient> ingredients) {
    if (ingredients == null || ingredients.isEmpty()) {
      return new TacoClassification(Collections.emptySet(), Collections.emptySet(),
          SpiceLevel.NONE);
    }
    Set<DietaryTag> tags = EnumSet.noneOf(DietaryTag.class);
    if (every(ingredients, DietaryTag.VEGAN)) {
      tags.add(DietaryTag.VEGAN);
      tags.add(DietaryTag.VEGETARIAN);
    }
    if (every(ingredients, DietaryTag.VEGETARIAN)) {
      tags.add(DietaryTag.VEGETARIAN);
    }
    if (every(ingredients, DietaryTag.GLUTEN_FREE)) {
      tags.add(DietaryTag.GLUTEN_FREE);
    }

    EnumSet<Allergen> allergens = EnumSet.noneOf(Allergen.class);
    SpiceLevel spice = SpiceLevel.NONE;
    for (Ingredient ingredient : ingredients) {
      if (ingredient != null) {
        if (ingredient.getAllergens() != null) {
          allergens.addAll(ingredient.getAllergens());
        }
        SpiceLevel level = ingredient.getSpice();
        if (level != null) {
          spice = spice.max(level);
        }
      }
    }
    return new TacoClassification(tags, allergens, spice);
  }

  private static boolean every(List<Ingredient> ingredients, DietaryTag tag) {
    for (Ingredient ingredient : ingredients) {
      if (ingredient == null || ingredient.getDietaryTags() == null
          || !ingredient.getDietaryTags().contains(tag)) {
        return false;
      }
    }
    return true;
  }

}