package tacos.messaging;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tacos.OrderStatus;
import tacos.TacoOrder;

/**
 * Translates the persistence entity into the broker-neutral event (TC-27).
 *
 * <p>Lives in the API, not in the contract: the contract must stay free of
 * Spring, Mongo and domain types, while this mapper is allowed to know both
 * sides. Only kitchen-safe snapshots cross here: names, counts, totals and
 * the city/state for routing. The street, ZIP, payment reference, user
 * object and any card data never leave this method.
 */
@Component
public class OrderEventMapper {

  public OrderEvent toCreated(TacoOrder order, String correlationId) {
    return OrderEvent.of(OrderEventType.ORDER_CREATED, payloadOf(order, null),
        correlationId);
  }

  public OrderEvent toStatusChanged(TacoOrder order, OrderStatus previous,
                                    String correlationId) {
    OrderEventPayload payload = payloadOf(order,
        previous == null ? null : previous.name());
    return OrderEvent.of(OrderEventType.STATUS_CHANGED, payload, correlationId);
  }

  public OrderEvent toCancelled(TacoOrder order, OrderStatus previous,
                                String correlationId) {
    OrderEventPayload payload = payloadOf(order,
        previous == null ? null : previous.name());
    return OrderEvent.of(OrderEventType.ORDER_CANCELLED, payload, correlationId);
  }

  private static OrderEventPayload payloadOf(TacoOrder order, String previousStatus) {
    List<String> names = order.getTacos() == null ? List.of()
        : order.getTacos().stream()
            .filter(t -> t != null)
            .map(t -> t.getName())
            .collect(Collectors.toList());
    int count = order.getTacos() == null ? 0 : order.getTacos().size();
    OrderEventPayload payload = new OrderEventPayload();
    payload.setOrderId(order.getId());
    payload.setStatus(order.getStatus() == null
        ? OrderStatus.CREATED.name() : order.getStatus().name());
    payload.setPreviousStatus(previousStatus);
    payload.setTacoCount(count);
    payload.setTacoNames(names);
    payload.setCurrency(order.getCurrency());
    payload.setTotal(order.getTotal());
    payload.setPlacedAt(order.getPlacedAt() == null ? null
        : order.getPlacedAt().toInstant());
    payload.setDeliveryCity(order.getDeliveryCity());
    payload.setDeliveryState(order.getDeliveryState());
    payload.setStationId(order.getStationId());
    return payload;
  }
}
