package tacos.workflow;

import javax.validation.constraints.NotNull;

import lombok.Data;
import tacos.OrderStatus;

/**
 * Body of {@code PATCH /api/orders/{id}/status} (TC-25).
 *
 * <p>Only the target status and an optional short reason travel here. Roles,
 * ownership and the legality of the move are decided server-side.
 */
@Data
public class OrderStatusChangeRequest {

  @NotNull(message = "status is required")
  private OrderStatus status;

  private String reason;
}
