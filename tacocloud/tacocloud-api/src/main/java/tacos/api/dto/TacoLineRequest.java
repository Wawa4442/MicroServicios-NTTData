package tacos.api.dto;

import java.util.List;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

import lombok.Data;

/**
 * One taco inside an order create/replace request: a name, the stable ids of
 * its ingredients and how many units the customer wants. Ingredient
 * resolution and pricing are performed by the service, never by a mapper.
 * Money is deliberately absent: the server owns it.
 */
@Data
public class TacoLineRequest {

  @NotBlank(message = "every taco needs a name")
  private String name;

  @NotEmpty(message = "every taco needs at least one ingredient")
  private List<@NotBlank(message = "ingredient ids must not be blank") String> ingredientIds;

  @Min(value = 1, message = "quantity must be at least 1")
  private int quantity = 1;

}