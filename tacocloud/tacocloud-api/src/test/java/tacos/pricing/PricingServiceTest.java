package tacos.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;

public class PricingServiceTest {

  private final PricingService pricing = new PricingService(new PricingProperties());

  @Test
  public void tc14_priceIsDecimalAndRoundsHalfUp() {
    // 1.00 base + 0.333 + 0.333 = 1.666 -> 1.67 (HALF_UP, scale 2)
    Taco taco = priced(new BigDecimal("0.333"), new BigDecimal("0.333"));
    pricing.priceLine(taco, 1);

    assertEquals(0, new BigDecimal("1.67").compareTo(taco.getUnitPriceAtPurchase()));
  }

  @Test
  public void tc14_twoUnitsDoubleTheLineSubtotal() {
    Taco one = priced(new BigDecimal("2.00"));
    pricing.priceLine(one, 1);
    Taco two = priced(new BigDecimal("2.00"));
    pricing.priceLine(two, 2);

    assertEquals(0, one.getSubtotal().multiply(new BigDecimal("2"))
        .compareTo(two.getSubtotal()), "two units must cost twice one unit");
  }

  @Test
  public void tc14_zero_orNegativeQuantity_isRejected() {
    assertThrows(InvalidQuantityException.class, () -> pricing.priceLine(priced(), 0));
    assertThrows(InvalidQuantityException.class, () -> pricing.priceLine(priced(), -3));
  }

  @Test
  public void tc14_quantityAboveMaximum_isRejected() {
    PricingProperties props = new PricingProperties();
    props.setMaxLineQuantity(5);
    assertThrows(InvalidQuantityException.class,
        () -> new PricingService(props).priceLine(priced(), 6));
  }

  @Test
  public void tc14_historicalSnapshotIsNotRewrittenByCatalogChanges() {
    Ingredient cheese = new Ingredient("CHED", "Cheddar", Type.CHEESE,
        new BigDecimal("0.90"), true, 100, 10);
    Taco line = new Taco();
    line.setName("Snapshot");
    line.setIngredients(Arrays.asList(cheese));
    pricing.priceLine(line, 1);
    BigDecimal frozen = line.getUnitPriceAtPurchase();

    // The catalog price changes later...
    cheese.setUnitPrice(new BigDecimal("9.99"));

    assertEquals(0, frozen.compareTo(line.getUnitPriceAtPurchase()),
        "an already-priced line keeps its purchase-time price");
  }

  private static Taco priced(BigDecimal... ingredientPrices) {
    Taco taco = new Taco();
    taco.setName("Test Taco");
    java.util.List<Ingredient> ingredients = new java.util.ArrayList<>();
    int i = 0;
    for (BigDecimal price : ingredientPrices) {
      ingredients.add(new Ingredient("I" + i++, "Ingredient", Type.PROTEIN,
          price, true, 10, 1));
    }
    taco.setIngredients(ingredients);
    return taco;
  }

}