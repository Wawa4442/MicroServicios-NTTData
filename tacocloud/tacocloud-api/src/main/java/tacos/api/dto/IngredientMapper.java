package tacos.api.dto;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import tacos.Ingredient;

/**
 * Transforms between the ingredient persistence entity and its request/response
 * contracts. Pure mapping: no repository access, no business rules.
 */
@Component
public class IngredientMapper {

  public Ingredient toEntity(String id, IngredientRequest request) {
    return new Ingredient(id, request.getName(), request.getType());
  }

  public IngredientResponse toResponse(Ingredient ingredient) {
    return IngredientResponse.from(ingredient);
  }

  public Flux<IngredientResponse> toResponses(Flux<Ingredient> ingredients) {
    return ingredients.map(IngredientResponse::from);
  }

}