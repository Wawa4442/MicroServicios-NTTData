package tacos.workflow;

import lombok.Data;

/**
 * Body of {@code POST /api/orders/{id}/cancel} (TC-25). The only thing the
 * customer says is <em>why</em>; the target is always CANCELLED and the
 * policy decides whether it is still early enough.
 */
@Data
public class OrderCancelRequest {

  private String reason;
}
