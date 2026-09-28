package tacos.api.dto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import lombok.Value;
import tacos.OrderStatus;
import tacos.TacoOrder;

/**
 * Safe output contract for an order. Never contains the embedded user, the
 * password, authorities, the payment PAN/CVV or any persistence-only field.
 * Money is server-computed (TC-14/TC-15) and read-only for clients.
 */
@Value
public class OrderResponse {

  private final String id;
  private final Date placedAt;
  private final String deliveryName;
  private final String deliveryStreet;
  private final String deliveryCity;
  private final String deliveryState;
  private final String deliveryZip;
  private final List<TacoLineResponse> tacos;
  private final String currency;
  private final BigDecimal subtotal;
  private final BigDecimal discount;
  private final String couponCode;
  private final BigDecimal total;
  private final OrderStatus status;
  private final Long version;

  public static OrderResponse from(TacoOrder order) {
    List<TacoLineResponse> lines = order.getTacos() == null
        ? java.util.Collections.emptyList()
        : order.getTacos().stream()
            .map(TacoLineResponse::from)
            .collect(java.util.stream.Collectors.toList());
    return new OrderResponse(order.getId(), order.getPlacedAt(),
        order.getDeliveryName(), order.getDeliveryStreet(),
        order.getDeliveryCity(), order.getDeliveryState(), order.getDeliveryZip(),
        lines, order.getCurrency(), order.getSubtotal(), order.getDiscount(),
        order.getCouponCode(), order.getTotal(),
        order.getStatus() == null ? OrderStatus.CREATED : order.getStatus(),
        order.getVersion());
  }

}