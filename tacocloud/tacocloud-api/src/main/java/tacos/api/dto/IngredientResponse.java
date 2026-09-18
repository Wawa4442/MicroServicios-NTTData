package tacos.api.dto;

import java.math.BigDecimal;

import lombok.Value;
import tacos.Ingredient;

/**
 * Safe output contract for an ingredient in the public catalog: stable id,
 * display name, type, sale price and availability. Operational metadata
 * (stock on hand, reorder level, version) is deliberately omitted.
 */
@Value
public class IngredientResponse {

  private final String id;
  private final String name;
  private final Ingredient.Type type;
  private final BigDecimal unitPrice;
  private final boolean available;

  public static IngredientResponse from(Ingredient ingredient) {
    return new IngredientResponse(ingredient.getId(), ingredient.getName(),
        ingredient.getType(), ingredient.getUnitPrice(), ingredient.isAvailable());
  }

}