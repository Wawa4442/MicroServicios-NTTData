package tacos.classification;

import java.util.List;

import org.springframework.stereotype.Service;

import tacos.Ingredient;

/**
 * Derives the dietary/allergen/spice classification of a taco from its
 * ingredients (TC-17). Thin wrapper over {@link TacoClassification} so the
 * rest of the application can inject one service instead of a static factory;
 * the rules themselves are pure and tested directly.
 */
@Service
public class TacoClassificationService {

  public TacoClassification classify(List<Ingredient> ingredients) {
    return TacoClassification.fromIngredients(ingredients);
  }

}