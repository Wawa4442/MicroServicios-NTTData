package tacos.api.dto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import lombok.Value;

import tacos.Taco;
import tacos.TacoOrder;

/**
 * One line of the customer's order history (TC-23).
 *
 * <p>Deliberately narrower than {@link OrderResponse}. A history list is a
 * browsing surface, not a receipt, so it carries what a customer looks for when
 * they ask "what did I order, and what did it cost" and nothing else: no
 * embedded user, no delivery address, no payment reference of any kind. The
 * detail route is the one that returns the address, and only to its owner.
 */
@Value
public class OrderSummaryResponse {

  private final String id;
  private final Date placedAt;
  private final List<String> tacoNames;
  private final int tacoCount;
  private final String currency;
  private final BigDecimal total;

  public static OrderSummaryResponse from(TacoOrder order) {
    List<Taco> lines = order.getTacos() == null ? List.of() : order.getTacos();
    return new OrderSummaryResponse(order.getId(), order.getPlacedAt(),
        lines.stream()
            .filter(line -> line != null)
            .map(Taco::getName)
            .collect(Collectors.toList()),
        lines.size(), order.getCurrency(), order.getTotal());
  }

}
