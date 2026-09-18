package tacos.api.dto;

import javax.validation.constraints.NotNull;

import lombok.Data;

/**
 * Operator inventory adjustment. {@code delta} may be negative (waste, sale
 * correction) but the resulting stock can never fall below zero.
 */
@Data
public class StockAdjustmentRequest {

  @NotNull(message = "delta is required")
  private Integer delta;

  private String reason;

}