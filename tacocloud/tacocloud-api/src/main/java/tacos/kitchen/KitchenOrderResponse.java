package tacos.kitchen;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import lombok.Value;
import tacos.OrderStatus;
import tacos.TacoOrder;

/**
 * Safe kitchen view of an order (TC-26).
 *
 * <p>The kitchen needs to cook, not to bill: this DTO carries the ticket
 * (taco names, counts, city/state for routing) plus the assignment and the
 * ETA, and nothing else. No street, no ZIP, no payment reference, no user
 * object, no password. A kitchen display that cannot see a card cannot leak
 * it.
 */
@Value
public class KitchenOrderResponse {

  private final String id;
  private final Date placedAt;
  private final OrderStatus status;
  private final List<String> tacoNames;
  private final int tacoCount;
  private final String deliveryCity;
  private final String deliveryState;
  private final String stationId;
  private final String cookId;
  private final int estimatedPrepMinutes;
  private final Long version;

  public static KitchenOrderResponse of(TacoOrder order, int estimatedPrepMinutes) {
    List<String> names = order.getTacos() == null ? List.of()
        : order.getTacos().stream()
            .filter(t -> t != null)
            .map(t -> t.getName())
            .collect(Collectors.toList());
    int count = order.getTacos() == null ? 0 : order.getTacos().size();
    return new KitchenOrderResponse(order.getId(), order.getPlacedAt(),
        order.getStatus() == null ? OrderStatus.CREATED : order.getStatus(),
        names, count, order.getDeliveryCity(), order.getDeliveryState(),
        order.getStationId(), order.getCookId(), estimatedPrepMinutes,
        order.getVersion());
  }
}
