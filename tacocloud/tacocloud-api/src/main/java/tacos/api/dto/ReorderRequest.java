package tacos.api.dto;

import javax.validation.constraints.NotBlank;

import lombok.Data;

/**
 * Input of {@code POST /api/orders/{id}/reorder} (TC-24).
 *
 * <p>Three fields, and the two that are missing are the interesting ones.
 * There is no card data and no payment token: the customer picks a payment
 * method the system already tokenized, and only its id travels. There is also
 * no way to ask for a specific price, a specific id, or a reuse of the original
 * order — the new order is priced by the current catalog and gets its own
 * identity, because a reorder is a new command, not a copy.
 */
@Data
public class ReorderRequest {

  /**
   * Required on purpose. Reordering with the original payment reference would
   * mean charging a token that may have expired or been revoked, and silently
   * creating an unpaid order would be worse.
   */
  @NotBlank(message = "paymentMethodId is required to reorder")
  private String paymentMethodId;

  /**
   * The customer accepts the current price. When the current total differs from
   * the original and this is false, the endpoint answers with a quote and
   * creates nothing.
   */
  private boolean confirmPriceChange;

  /**
   * Makes the call idempotent. Two retries with the same key create one order;
   * without a key, every retry is a new order, which is the correct default for
   * a command that places an order.
   */
  private String idempotencyKey;

}
