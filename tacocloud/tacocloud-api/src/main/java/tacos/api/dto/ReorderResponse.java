package tacos.api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Value;

/**
 * Answer of a reorder attempt (TC-24).
 *
 * <p>The status is what makes the endpoint honest: a reorder that needs the
 * customer to agree to a different price is not an order yet, and returning it
 * with a 200 and no {@code order} is the only way to say so without lying.
 * {@code previousTotal}/{@code currentTotal} and the per-line {@code
 * differences} are always present, so the UI can show what changed and why
 * before asking for the confirmation.
 */
@Value
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReorderResponse {

  /** What the caller got: a price to approve, or a placed order. */
  public enum Status {
    /** Nothing was created; the current price needs the customer's agreement. */
    QUOTE,
    /** A new order was created from the historical one. */
    CONFIRMED
  }

  private final Status status;
  private final String sourceOrderId;
  private final BigDecimal previousTotal;
  private final BigDecimal currentTotal;
  private final String currency;
  private final List<Difference> differences;
  private final OrderQuoteResponse quote;
  private final OrderResponse order;

  public static ReorderResponse awaitingConfirmation(String sourceOrderId,
      BigDecimal previousTotal, OrderQuoteResponse quote, List<Difference> differences) {
    return new ReorderResponse(Status.QUOTE, sourceOrderId, previousTotal,
        quote.getTotal(), quote.getCurrency(), differences, quote, null);
  }

  public static ReorderResponse placed(String sourceOrderId, BigDecimal previousTotal,
      OrderQuoteResponse quote, List<Difference> differences, OrderResponse order) {
    return new ReorderResponse(Status.CONFIRMED, sourceOrderId, previousTotal,
        quote.getTotal(), quote.getCurrency(), differences, quote, order);
  }

  /**
   * One line whose price moved since the original order. Only lines that
   * actually changed appear, so an empty list genuinely means "same price".
   */
  @Value
  public static class Difference {

    private final String tacoName;
    private final int quantity;
    private final BigDecimal previousUnitPrice;
    private final BigDecimal currentUnitPrice;
    private final BigDecimal previousSubtotal;
    private final BigDecimal currentSubtotal;

  }

}
