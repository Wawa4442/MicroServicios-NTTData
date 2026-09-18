package tacos.api.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import lombok.Data;
import tacos.Ingredient;

/**
 * Input contract for creating or replacing an ingredient. The kitchen id is
 * server-owned: it is never accepted from the body (POST) nor expected in it
 * (PUT, where it comes from the path).
 */
@Data
public class IngredientRequest {

  @NotBlank(message = "name is required")
  private String name;

  @NotNull(message = "type is required")
  private Ingredient.Type type;

}