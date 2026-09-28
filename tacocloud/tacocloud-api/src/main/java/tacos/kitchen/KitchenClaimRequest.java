package tacos.kitchen;

import lombok.Data;

/**
 * Body of {@code POST /api/kitchen/queue/claim} (TC-26). Both fields are
 * optional: when the station does not say who it is, the authenticated
 * identity is used, so the assignment is never anonymous.
 */
@Data
public class KitchenClaimRequest {

  private String stationId;
  private String cookId;
}
