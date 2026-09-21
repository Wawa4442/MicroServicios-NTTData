package tacos.api.dto;

import java.util.List;

import javax.validation.constraints.NotEmpty;

import lombok.Data;

/**
 * Body of {@code POST /api/tacos/validate}: the ingredient ids of a proposed
 * design. Only ids travel in; dietary labels, allergen lists or spice levels
 * are never accepted from the client because they are derived server-side
 * (TC-17/TC-18).
 */
@Data
public class TacoDesignValidationRequest {

  @NotEmpty(message = "a taco design needs at least one ingredient")
  private List<String> ingredientIds;

}