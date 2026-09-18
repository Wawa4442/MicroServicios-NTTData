package tacos.api.dto;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import lombok.Data;

/**
 * Input contract for creating or replacing an order. Deliberately free of
 * server-owned fields: there is no id, placedAt, status, total, userId, PAN or
 * CVV here. Payment is referenced by its stable id only; the server resolves
 * it. The DTO carries no {@code @Document} and no security interfaces.
 */
@Data
public class OrderCreateRequest {

  @NotBlank(message = "deliveryName is required")
  private String deliveryName;

  @NotBlank(message = "deliveryStreet is required")
  private String deliveryStreet;

  @NotBlank(message = "deliveryCity is required")
  private String deliveryCity;

  @NotBlank(message = "deliveryState is required")
  @Pattern(regexp = "[A-Z]{2}", message = "deliveryState must be a two-letter state code")
  private String deliveryState;

  @NotBlank(message = "deliveryZip is required")
  @Pattern(regexp = "\\d{5}", message = "deliveryZip must be a five-digit postal code")
  private String deliveryZip;

  @NotEmpty(message = "an order needs at least one taco")
  private List<@Valid TacoLineRequest> tacos;

  private String paymentMethodId;

}