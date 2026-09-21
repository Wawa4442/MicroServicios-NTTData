package tacos.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;

/**
 * TC-18 Taco Physics: composable rules, all violations aggregated (no
 * fail-fast), and new rules pluggable without touching the validator.
 */
public class TacoValidatorTest {

  private static final List<Ingredient> FLTO_GRBF = resolved(
      wrap("FLTO"),
      protein("GRBF"));

  private static TacoPhysicsProperties physics() {
    TacoPhysicsProperties properties = new TacoPhysicsProperties();
    properties.setMinIngredients(2);
    properties.setMaxIngredients(12);
    properties.setGhostPepperIngredientId("GHPR");
    properties.setDrinkIngredientIds(List.of("WATR"));
    properties.setVeganNoMeatEnabled(true);
    return properties;
  }

  private static TacoValidator validator() {
    return validator(physics());
  }

  private static TacoValidator validator(TacoPhysicsProperties properties) {
    return new TacoValidator(List.of(
        new SingleBaseRule(),
        new IngredientCountRule(properties),
        new NoDuplicateIngredientRule(),
        new AvailableIngredientsRule(),
        new GhostPepperNeedsDrinkRule(properties),
        new VeganNoMeatRule(properties)));
  }

  private static TacoValidator validatorWithExtra(TacoRule extra, TacoPhysicsProperties properties) {
    List<TacoRule> rules = new java.util.ArrayList<>(List.of(
        new SingleBaseRule(),
        new IngredientCountRule(properties),
        new NoDuplicateIngredientRule(),
        new AvailableIngredientsRule(),
        new GhostPepperNeedsDrinkRule(properties),
        new VeganNoMeatRule(properties)));
    rules.add(extra);
    return new TacoValidator(rules);
  }

  @Test
  public void aValidTwoIngredientTaco_passesEveryRule() {
    assertTrue(validator().violations(FLTO_GRBF).isEmpty());
  }

  @Test
  public void noBaseCommitsSingleBase() {
    assertTrue(codes(resolved(protein("GRBF"), veggie("CARM"))).contains("SINGLE_BASE"));
  }

  @Test
  public void twoBasesCommitSingleBase() {
    assertTrue(codes(resolved(wrap("FLTO"), wrap("SFTW"), protein("GRBF")))
        .contains("SINGLE_BASE"));
  }

  @Test
  public void oneIngredientCommitsTooFew() {
    assertTrue(codes(resolved(wrap("FLTO"))).contains("TOO_FEW_INGREDIENTS"));
  }

  @Test
  public void overTheLimitCommitsTooMany() {
    TacoPhysicsProperties config = physics();
    config.setMinIngredients(2);
    config.setMaxIngredients(3);
    assertTrue(codes(validator(config), resolved(wrap("FLTO"), protein("GRBF"),
        veggie("CARM"), veggie("LETC"))).contains("TOO_MANY_INGREDIENTS"));
  }

  @Test
  public void duplicateIngredientCommitsDuplicate() {
    assertTrue(codes(resolved(wrap("FLTO"), wrap("FLTO"), protein("GRBF")))
        .contains("DUPLICATE_INGREDIENT"));
  }

  @Test
  public void unavailableIngredientCommitsUnavailable() {
    Ingredient black = wrap("FLTO");
    Ingredient meat = protein("GRBF");
    meat.setAvailable(false);
    assertTrue(codes(resolved(black, meat)).contains("UNAVAILABLE_INGREDIENT"));
  }

  @Test
  public void ghostPepperWithoutDrink_commits_andWithDrinkPasses() {
    Ingredient ghost = ingredient("GHPR", Type.VEGGIES, true);
    Ingredient drink = ingredient("WATR", Type.VEGGIES, true);

    assertTrue(codes(resolved(ghost, wrap("FLTO"))).contains("GHOST_PEPPER_NEEDS_DRINK"));
    assertTrue(validator().violations(resolved(ghost, drink, wrap("FLTO"))).isEmpty());
  }

  @Test
  public void ghostRule_isInertWhenNoPepperConfigured() {
    TacoPhysicsProperties noGhost = physics();
    noGhost.setGhostPepperIngredientId(null);
    Ingredient ghost = ingredient("GHPR", Type.VEGGIES, true);

    List<RuleViolation> violations =
        validator(noGhost).violations(resolved(ghost, wrap("FLTO")));

    assertTrue(violations.stream().noneMatch(
        v -> v.getCode().equals("GHOST_PEPPER_NEEDS_DRINK")),
        "an unconfigured ghost pepper id deactivates the fun rule");
  }

  @Test
  public void veganAndMeatTogether_isRejected_unlessDisabled() {
    Ingredient tofu = ingredient("TFR", Type.VEGGIES, true);
    tofu.setDietaryTags(Set.of(DietaryTag.VEGAN));
    Ingredient carnitas = ingredient("CARN", Type.PROTEIN, true);
    carnitas.setAllergens(Set.of(Allergen.MEAT));

    assertTrue(codes(resolved(tofu, carnitas)).contains("VEGAN_NO_MEAT"));
    TacoPhysicsProperties relaxed = physics();
    relaxed.setVeganNoMeatEnabled(false);
    assertTrue(validator(relaxed).violations(resolved(tofu, carnitas)).stream()
            .noneMatch(v -> v.getCode().equals("VEGAN_NO_MEAT")),
        "the mixed design is allowed when the rule is disabled");
  }

  @Test
  public void allViolationsAreAggregated_noFailFast() {
    // Two wraps AND a duplicate AND a too-many: every rule contributes.
    List<String> codes = codes(resolved(
        wrap("FLTO"), wrap("FLTO"), protein("GRBF"), veggie("CARM"), veggie("LETC")));
    assertTrue(codes.containsAll(List.of("SINGLE_BASE", "DUPLICATE_INGREDIENT")));
  }

  @Test
  public void validateOrThrow_combinesEveryViolation_inOneException() {
    TacoDesignInvalidException ex = assertThrows(TacoDesignInvalidException.class,
        () -> validator().validateOrThrow(resolved(protein("GRBF"))));

    assertEquals(List.of("SINGLE_BASE", "TOO_FEW_INGREDIENTS"),
        ex.getViolations().stream().map(RuleViolation::getCode).collect(Collectors.toList()));
  }

  @Test
  public void newRulesExtendTheValidation_withoutChangingTheValidator() {
    TacoRule custom = mockRuleAlwaysViolates();
    List<RuleViolation> aggregated =
        validatorWithExtra(custom, physics()).violations(FLTO_GRBF);
    assertEquals(Set.of("CUSTOM"),
        aggregated.stream().map(RuleViolation::getCode).collect(Collectors.toSet()));
  }

  @Test
  public void nullIngredients_areHandledGracefully() {
    // No base and no ingredients are still structural violations, but they must
    // be reported as rule results, never as an NPE.
    assertTrue(validator().violations(null).stream()
        .map(RuleViolation::getCode)
        .collect(Collectors.toSet())
        .containsAll(List.of("SINGLE_BASE", "TOO_FEW_INGREDIENTS")));
  }

  private static TacoRule mockRuleAlwaysViolates() {
    return ingredients -> Collections.singletonList(
        RuleViolation.of("CUSTOM", "configured on the fly"));
  }

  private static List<String> codes(List<Ingredient> ingredients) {
    return codes(validator(), ingredients);
  }

  private static List<String> codes(TacoValidator verifier, List<Ingredient> ingredients) {
    return verifier.violations(ingredients).stream()
        .map(RuleViolation::getCode)
        .collect(Collectors.toList());
  }

  private static List<Ingredient> resolved(Ingredient... ingredients) {
    return Arrays.asList(ingredients);
  }

  private static Ingredient wrap(String id) {
    return ingredient(id, Type.WRAP, true);
  }

  private static Ingredient protein(String id) {
    return ingredient(id, Type.PROTEIN, true);
  }

  private static Ingredient veggie(String id) {
    return ingredient(id, Type.VEGGIES, true);
  }

  private static Ingredient ingredient(String id, Type type, boolean available) {
    Ingredient ingredient = new Ingredient(id, id, type);
    ingredient.setAvailable(available);
    ingredient.setDietaryTags(Set.of());
    ingredient.setAllergens(Set.of());
    ingredient.setSpice(SpiceLevel.NONE);
    return ingredient;
  }

}