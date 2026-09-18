package tacos.api.dto;

import java.math.BigDecimal;

import lombok.Value;
import tacos.Ingredient;

/**
 * Operator-only view of a catalog ingredient: adds stock, reorder level and
 * the optimistic-locking version. Never used for customer-facing responses.
 */
@Value
public class IngredientAdminResponse {

  private final String id;
  private final String name;
  private final Ingredient.Type type;
  private final BigDecimal unitPrice;
  private final boolean available;
  private final int stockOnHand;
  private final int reorderLevel;
  private final Long version;

  public static IngredientAdminResponse from(Ingredient ingredient) {
    return new IngredientAdminResponse(ingredient.getId(), ingredient.getName(),
        ingredient.getType(), ingredient.getUnitPrice(), ingredient.isAvailable(),
        ingredient.getStockOnHand(), ingredient.getReorderLevel(), ingredient.getVersion());
  }

}