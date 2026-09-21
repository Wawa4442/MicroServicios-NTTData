package tacos.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Value;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.classification.TacoClassification;

/**
 * Output representation of one order line: name, ingredient references, how
 * many units were bought and the frozen unit price/subtotal. Money here is a
 * historical snapshot, not a live catalog value.
 *
 * <p>The dietary/allergen/spice fields are derived from the embedded
 * ingredients (TC-17); they never come from the client.
 */
@Value
public class TacoLineResponse {

  private final String name;
  private final List<IngredientResponse> ingredients;
  private final int quantity;
  private final BigDecimal unitPriceAtPurchase;
  private final BigDecimal subtotal;
  private final List<DietaryTag> dietaryTags;
  private final List<Allergen> allergens;
  private final SpiceLevel spiceLevel;

  public static TacoLineResponse from(Taco taco) {
    List<IngredientResponse> references = taco.getIngredients() == null
        ? java.util.Collections.emptyList()
        : taco.getIngredients().stream()
            .map(IngredientResponse::from)
            .collect(java.util.stream.Collectors.toList());
    TacoClassification classification = TacoClassification.fromIngredients(
        taco.getIngredients());
    return new TacoLineResponse(taco.getName(), references, taco.getQuantity(),
        taco.getUnitPriceAtPurchase(), taco.getSubtotal(),
        new ArrayList<>(classification.getDietaryTags()),
        new ArrayList<>(classification.getAllergens()),
        classification.getSpiceLevel());
  }

}