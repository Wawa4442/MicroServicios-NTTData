package tacos.api.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Value;
import tacos.Taco;

/**
 * Output representation of one order line: name, ingredient references, how
 * many units were bought and the frozen unit price/subtotal. Money here is a
 * historical snapshot, not a live catalog value.
 */
@Value
public class TacoLineResponse {

  private final String name;
  private final List<IngredientResponse> ingredients;
  private final int quantity;
  private final BigDecimal unitPriceAtPurchase;
  private final BigDecimal subtotal;

  public static TacoLineResponse from(Taco taco) {
    List<IngredientResponse> references = taco.getIngredients() == null
        ? java.util.Collections.emptyList()
        : taco.getIngredients().stream()
            .map(IngredientResponse::from)
            .collect(java.util.stream.Collectors.toList());
    return new TacoLineResponse(taco.getName(), references, taco.getQuantity(),
        taco.getUnitPriceAtPurchase(), taco.getSubtotal());
  }

}