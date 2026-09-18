package tacos.api.dto;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;

/**
 * Transforms between the order persistence entity and its request/response
 * contracts. The mapper only transforms data it is given; ingredient or payment
 * resolution (which needs repositories) belongs to the service layer.
 *
 * <p>Card data never reaches the entity: {@code applyPayment} only records the
 * opaque {@code paymentMethodId} that references an already-tokenized
 * {@link PaymentMethod}.
 */
@Component
public class OrderMapper {

  public TacoOrder toEntity(OrderCreateRequest request, List<Taco> tacos,
                            User user, PaymentMethod payment) {
    TacoOrder order = new TacoOrder();
    order.setDeliveryName(request.getDeliveryName());
    order.setDeliveryStreet(request.getDeliveryStreet());
    order.setDeliveryCity(request.getDeliveryCity());
    order.setDeliveryState(request.getDeliveryState());
    order.setDeliveryZip(request.getDeliveryZip());
    order.setPlacedAt(new Date());
    order.setTacos(new ArrayList<>(tacos == null ? List.of() : tacos));
    order.setUser(user);
    applyPayment(order, payment);
    return order;
  }

  public TacoOrder merge(TacoOrder existing, OrderCreateRequest request,
                         List<Taco> tacos, PaymentMethod paymentOrNull) {
    TacoOrder merged = new TacoOrder();
    merged.setId(existing.getId());
    merged.setPlacedAt(existing.getPlacedAt());
    merged.setUser(existing.getUser());
    merged.setDeliveryName(request.getDeliveryName());
    merged.setDeliveryStreet(request.getDeliveryStreet());
    merged.setDeliveryCity(request.getDeliveryCity());
    merged.setDeliveryState(request.getDeliveryState());
    merged.setDeliveryZip(request.getDeliveryZip());
    merged.setTacos(new ArrayList<>(tacos == null ? List.of() : tacos));
    merged.setPaymentMethodId(
        paymentOrNull != null ? paymentOrNull.getId() : existing.getPaymentMethodId());
    return merged;
  }

  public OrderResponse toResponse(TacoOrder order) {
    return OrderResponse.from(order);
  }

  private void applyPayment(TacoOrder order, PaymentMethod payment) {
    if (payment == null) {
      return;
    }
    order.setPaymentMethodId(payment.getId());
  }

}