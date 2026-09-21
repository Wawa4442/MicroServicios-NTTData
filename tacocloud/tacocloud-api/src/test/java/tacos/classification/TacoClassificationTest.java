package tacos.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;

/**
 * TC-17 classification policy: a dietary tag is only true when every
 * ingredient carries it, allergens are the exact union, spice is the max.
 * Legacy documents missing the new metadata stay null-safe.
 */
public class TacoClassificationTest {

  @Test
  public void emptyTaco_isUnlabeled() {
    TacoClassification classification = TacoClassification.fromIngredients(List.of());
    assertTrue(classification.getDietaryTags().isEmpty());
    assertTrue(classification.getAllergens().isEmpty());
    assertEquals(SpiceLevel.NONE, classification.getSpiceLevel());
  }

  @Test
  public void nullTaco_isUnlabeled() {
    TacoClassification classification = TacoClassification.fromIngredients(null);
    assertTrue(classification.getDietaryTags().isEmpty());
    assertEquals(SpiceLevel.NONE, classification.getSpiceLevel());
  }

  @Test
  public void veganAndVegetarian_isTrueOnlyWhenEveryIngredientComplies() {
    Ingredient beans = ingredient("BEEF_FREE", Set.of(DietaryTag.VEGAN, DietaryTag.VEGETARIAN),
        Set.of(), SpiceLevel.NONE);
    Ingredient cheese = ingredient("CARD", Set.of(DietaryTag.VEGETARIAN),
        Set.of(Allergen.DAIRY), SpiceLevel.NONE);

    TacoClassification classification =
        TacoClassification.fromIngredients(List.of(beans, cheese));

    assertTrue(classification.getDietaryTags().contains(DietaryTag.VEGETARIAN),
        "both ingredients are vegetarian");
    assertTrue(!classification.getDietaryTags().contains(DietaryTag.VEGAN),
        "the cheese makes the taco non-vegan");
  }

  @Test
  public void veganImpliesVegetarian_butVegetarianDoesNotImplyVegan() {
    Ingredient vegan = ingredient("VG", Set.of(DietaryTag.VEGAN), Set.of(), SpiceLevel.NONE);
    TacoClassification allVegan = TacoClassification.fromIngredients(List.of(vegan));
    assertTrue(allVegan.getDietaryTags().contains(DietaryTag.VEGAN));
    assertTrue(allVegan.getDietaryTags().contains(DietaryTag.VEGETARIAN));
  }

  @Test
  public void glutenFree_requiresEveryIngredient_carryingTheTag() {
    Ingredient rice = ingredient("RICE", Set.of(DietaryTag.GLUTEN_FREE), Set.of(), SpiceLevel.NONE);
    Ingredient wheat = ingredient("WRTR", Set.of(), Set.of(Allergen.GLUTEN), SpiceLevel.NONE);

    TacoClassification all = TacoClassification.fromIngredients(List.of(rice, wheat));
    assertTrue(!all.getDietaryTags().contains(DietaryTag.GLUTEN_FREE),
        "one wheat tortilla breaks the gluten-free claim");
  }

  @Test
  public void allergens_areTheExactUnion_acrossIngredients() {
    Ingredient meat = ingredient("GRBF", Set.of(), Set.of(Allergen.MEAT), SpiceLevel.NONE);
    Ingredient cheese = ingredient("CHED", Set.of(),
        Set.of(Allergen.DAIRY, Allergen.MEAT), SpiceLevel.NONE);

    TacoClassification classification =
        TacoClassification.fromIngredients(List.of(meat, cheese));

    assertEquals(EnumSet.of(Allergen.DAIRY, Allergen.MEAT),
        classification.getAllergens());
  }

  @Test
  public void spiceLevel_isTheMaximum() {
    Ingredient mild = ingredient("SLSA", Set.of(), Set.of(), SpiceLevel.MILD);
    Ingredient hot = ingredient("GHPR", Set.of(), Set.of(), SpiceLevel.EXTRA_HOT);

    assertEquals(SpiceLevel.EXTRA_HOT,
        TacoClassification.fromIngredients(List.of(mild, hot)).getSpiceLevel());
  }

  @Test
  public void legacyDocuments_missingMetadata_doNotCrash() {
    Ingredient legacy = new Ingredient("LEG", "Legacy", Type.WRAP);
    legacy.setDietaryTags(null);
    legacy.setAllergens(null);
    legacy.setSpice(null);

    TacoClassification classification =
        TacoClassification.fromIngredients(List.of(legacy));

    assertTrue(classification.getDietaryTags().isEmpty(),
        "a legacy ingredient without metadata never earns a tag");
    assertTrue(classification.getAllergens().isEmpty());
    assertEquals(SpiceLevel.NONE, classification.getSpiceLevel());
  }

  @Test
  public void serviceDelegatesToThePureFactory() {
    Ingredient tofu = ingredient("TFR", Set.of(DietaryTag.VEGAN), Set.of(Allergen.SOY),
        SpiceLevel.MILD);
    TacoClassification classification =
        new TacoClassificationService().classify(List.of(tofu));

    assertTrue(classification.getDietaryTags().contains(DietaryTag.VEGAN));
    assertEquals(EnumSet.of(Allergen.SOY), classification.getAllergens());
    assertEquals(SpiceLevel.MILD, classification.getSpiceLevel());
  }

  private static Ingredient ingredient(String id, Set<DietaryTag> tags,
      Set<Allergen> allergens, SpiceLevel spice) {
    Ingredient ingredient = new Ingredient(id, id, Type.VEGGIES);
    ingredient.setDietaryTags(tags);
    ingredient.setAllergens(allergens);
    ingredient.setSpice(spice);
    return ingredient;
  }

}