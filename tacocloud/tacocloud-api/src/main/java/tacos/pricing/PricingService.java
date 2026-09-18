package tacos.pricing;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import tacos.Ingredient;
import tacos.Taco;

/**
 * Server-side pricing (TC-14). The client may send quantities but never money:
 * unit price, subtotal and totals are all computed here from the current
 * catalog, using an explicit rounding mode and currency.
 *
 * <p>Policy: {@code unitPrice = baseTacoFee + sum(ingredient.unitPrice)},
 * rounded half-up to the configured scale. Every line keeps the price used at
 * purchase time, so later catalog edits do not rewrite historical orders.
 */
@Service
public class PricingService {

  private final PricingProperties properties;

  public PricingService(PricingProperties properties) {
    this.properties = properties;
  }

  public String currency() {
    return properties.getCurrency();
  }

  /**
   * Prices one order line: validates the quantity, freezes the unit price and
   * computes the line subtotal.
   */
  public Taco priceLine(Taco taco, int quantity) {
    if (quantity < 1) {
      throw new InvalidQuantityException("quantity must be at least 1");
    }
    if (quantity > properties.getMaxLineQuantity()) {
      throw new InvalidQuantityException(
          "quantity must not exceed " + properties.getMaxLineQuantity());
    }
    BigDecimal unitPrice = unitPriceFor(taco.getIngredients());
    BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
    taco.setQuantity(quantity);
    taco.setUnitPriceAtPurchase(unitPrice);
    taco.setSubtotal(scale(subtotal));
    return taco;
  }

  /** Base fee plus the current price of every ingredient, rounded once. */
  public BigDecimal unitPriceFor(List<Ingredient> ingredients) {
    BigDecimal sum = properties.getBaseTacoFee() == null
        ? BigDecimal.ZERO : properties.getBaseTacoFee();
    if (ingredients != null) {
      for (Ingredient ingredient : ingredients) {
        if (ingredient != null && ingredient.getUnitPrice() != null) {
          sum = sum.add(ingredient.getUnitPrice());
        }
      }
    }
    return scale(sum);
  }

  /** Sum of the already-frozen line subtotals. */
  public BigDecimal subtotalOf(List<Taco> lines) {
    BigDecimal sum = BigDecimal.ZERO;
    if (lines != null) {
      for (Taco line : lines) {
        if (line != null && line.getSubtotal() != null) {
          sum = sum.add(line.getSubtotal());
        }
      }
    }
    return scale(sum);
  }

  /** Zero money in the configured currency scale. */
  public BigDecimal zero() {
    return scale(BigDecimal.ZERO);
  }

  private BigDecimal scale(BigDecimal value) {
    return value.setScale(properties.getScale(), properties.getRounding());
  }

}