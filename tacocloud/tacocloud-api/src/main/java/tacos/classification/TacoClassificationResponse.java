package tacos.classification;

import java.util.ArrayList;
import java.util.List;

import lombok.Value;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;

/**
 * Wire form of {@link TacoClassification}. Sorted lists keep the JSON stable
 * and deterministic across requests.
 */
@Value
public class TacoClassificationResponse {

  private final List<DietaryTag> dietaryTags;
  private final List<Allergen> allergens;
  private final SpiceLevel spiceLevel;

  public static TacoClassificationResponse from(TacoClassification classification) {
    return new TacoClassificationResponse(
        new ArrayList<>(classification.getDietaryTags()),
        new ArrayList<>(classification.getAllergens()),
        classification.getSpiceLevel());
  }

}