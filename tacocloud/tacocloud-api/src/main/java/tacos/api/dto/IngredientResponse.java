package tacos.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Value;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.SpiceLevel;

/**
 * Safe output contract for an ingredient in the public catalog (TC-13/TC-17):
 * stable id, display name, type, sale price, availability and the dietary
 * metadata. Operational metadata (stock on hand, reorder level, version) is
 * deliberately omitted: normal customers only need what the catalog shows.
 */
@Value
public class IngredientResponse {

  private final String id;
  private final String name;
  private final Ingredient.Type type;
  private final BigDecimal unitPrice;
  private final boolean available;
  private final List<DietaryTag> dietaryTags;
  private final List<Allergen> allergens;
  private final SpiceLevel spice;

  public static IngredientResponse from(Ingredient ingredient) {
    return new IngredientResponse(ingredient.getId(), ingredient.getName(),
        ingredient.getType(), ingredient.getUnitPrice(), ingredient.isAvailable(),
        ingredient.getDietaryTags() == null ? List.of()
            : new ArrayList<>(ingredient.getDietaryTags()),
        ingredient.getAllergens() == null ? List.of()
            : new ArrayList<>(ingredient.getAllergens()),
        ingredient.getSpice() == null ? SpiceLevel.NONE : ingredient.getSpice());
  }

}