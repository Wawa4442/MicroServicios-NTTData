package tacos.api.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Value;
import tacos.TacoOrder;

/**
 * Output of {@code POST /api/orders/quote} (TC-14/TC-15/TC-18). All money is
 * server-computed from the current catalog and the coupon engine; the quote
 * never creates an order, never reserves stock and never persists anything.
 */
@Value
public class OrderQuoteResponse {

  private final List<TacoLineResponse> tacos;
  private final String currency;
  private final BigDecimal subtotal;
  private final String couponCode;
  private final BigDecimal discount;
  private final BigDecimal total;

  public static OrderQuoteResponse of(TacoOrder order) {
    return new OrderQuoteResponse(order.getTacos() == null
            ? java.util.Collections.emptyList()
            : order.getTacos().stream()
                .map(TacoLineResponse::from)
                .collect(java.util.stream.Collectors.toList()),
        order.getCurrency(), order.getSubtotal(), order.getCouponCode(),
        order.getDiscount(), order.getTotal());
  }

}