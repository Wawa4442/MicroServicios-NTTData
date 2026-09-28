package tacos.reorder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderQuoteResponse;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.ReorderRequest;
import tacos.api.dto.ReorderResponse;
import tacos.api.dto.TacoLineRequest;
import tacos.api.dto.TacoLineResponse;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEventMapper;
import tacos.outbox.OutboxService;
import tacos.web.api.AuthenticationRequiredException;
import tacos.web.api.CallerIdentity;
import tacos.web.api.OrderAccessDeniedException;
import tacos.web.api.OrderApiService;
import tacos.web.api.OrderNotFoundException;

/**
 * "Order it again" (TC-24).
 *
 * <p>The implementation is deliberately thin: it turns the historical order
 * into a normal create request and hands it to {@link OrderApiService}, so a
 * reorder is priced, validated and reserved by exactly the same code as a first
 * order. That is the point. A reorder that had its own copy of the pricing
 * rules would be a second implementation that drifts from the first, and the
 * customer's "again" would quietly mean something different from their
 * original order.
 *
 * <p>What changes when you order again is the source of the data:
 *
 * <ul>
 *   <li><b>Ingredients are re-resolved, not replayed.</b> The request carries
 *       the ingredient <em>ids</em> the order froze, and the service loads the
 *       current version of each one. A price that went up, an ingredient that
 *       was retired (422) and one that went out of stock (422) are all decided
 *       now, not then.</li>
 *   <li><b>Money is recomputed</b> from the current catalog, so the new total
 *       can differ from the old one. When it does and the customer has not
 *       agreed, nothing is created and the caller gets a quote to approve.</li>
 *   <li><b>The payment method is the customer's choice</b>, sent in the request
 *       and validated for ownership like any other. The original order's
 *       reference is never copied forward: a token from months ago may be
 *       expired or revoked, and silently charging a card the customer did not
 *       pick is not a kindness.</li>
 *   <li><b>The original order is never touched.</b> A new document is built and
 *       gets a new id, a new {@code placedAt} and its own reservation.</li>
 * </ul>
 */
@Service
public class ReorderService {

  private final OrderRepository orders;
  private final OrderApiService orderApi;
  private final OutboxService outbox;
  private final OrderEventMapper events;

  public ReorderService(OrderRepository orders, OrderApiService orderApi,
                        OutboxService outbox, OrderEventMapper events) {
    this.orders = orders;
    this.orderApi = orderApi;
    this.outbox = outbox;
    this.events = events;
  }

  public Mono<ReorderResponse> reorder(String orderId, ReorderRequest request,
      CallerIdentity caller) {
    if (request.getPaymentMethodId() == null
        || request.getPaymentMethodId().trim().isEmpty()) {
      return Mono.error(new ReorderPaymentMethodRequiredException());
    }
    return orders.findById(orderId)
        .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
        .flatMap(source -> requireAccess(source, caller)
            .flatMap(accessible -> evaluate(accessible, request, caller)));
  }

  private Mono<ReorderResponse> evaluate(TacoOrder source, ReorderRequest request,
      CallerIdentity caller) {
    OrderCreateRequest replay = asCreateRequest(source, request);
    return orderApi.quoteOrder(replay, caller)
        .flatMap(quote -> {
          List<ReorderResponse.Difference> differences = differences(source, quote);
          if (totalsDiffer(source, quote) && !request.isConfirmPriceChange()) {
            return Mono.just(ReorderResponse.awaitingConfirmation(source.getId(),
                source.getTotal(), quote, differences));
          }
          return orderApi.createOrder(replay, caller)
              .flatMap(created -> outbox.append(
                  events.toCreated(created, request.getIdempotencyKey()))
                  .thenReturn(ReorderResponse.placed(source.getId(), source.getTotal(),
                      quote, differences, OrderResponse.from(created))));
        });
  }

  /**
   * The historical order expressed as a create request. The delivery address
   * comes from the order rather than from the client, so reordering cannot be
   * used to redirect a past order to an address it was never sent to.
   *
   * <p>The coupon is deliberately not carried over. A promotion that was valid
   * when the order was placed may be gone, and re-applying it optimistically
   * would quote a total the coupon engine would then have to reject. The quote
   * the customer sees is the honest current price.
   */
  private OrderCreateRequest asCreateRequest(TacoOrder source, ReorderRequest request) {
    OrderCreateRequest replay = new OrderCreateRequest();
    replay.setDeliveryName(source.getDeliveryName());
    replay.setDeliveryStreet(source.getDeliveryStreet());
    replay.setDeliveryCity(source.getDeliveryCity());
    replay.setDeliveryState(source.getDeliveryState());
    replay.setDeliveryZip(source.getDeliveryZip());
    replay.setTacos(linesOf(source));
    replay.setPaymentMethodId(request.getPaymentMethodId().trim());
    replay.setIdempotencyKey(request.getIdempotencyKey());
    return replay;
  }

  private List<TacoLineRequest> linesOf(TacoOrder source) {
    List<Taco> ordered = source.getTacos() == null ? List.of() : source.getTacos();
    return ordered.stream()
        .map(line -> {
          TacoLineRequest request = new TacoLineRequest();
          request.setName(line.getName());
          request.setQuantity(Math.max(1, line.getQuantity()));
          request.setIngredientIds(line.getIngredients() == null ? List.of()
              : line.getIngredients().stream()
                  .filter(ingredient -> ingredient != null && ingredient.getId() != null)
                  .map(Ingredient::getId)
                  .collect(Collectors.toList()));
          return request;
        })
        .collect(Collectors.toList());
  }

  /**
   * Lines whose money moved. Compared by position because the replay keeps the
   * original line order, so line <i>i</i> of the quote is line <i>i</i> of the
   * old order — a name-based join would be ambiguous as soon as a customer
   * ordered the same design twice.
   */
  private static List<ReorderResponse.Difference> differences(TacoOrder source,
      OrderQuoteResponse quote) {
    List<Taco> before = source.getTacos() == null ? List.of() : source.getTacos();
    List<TacoLineResponse> after = quote.getTacos() == null ? List.of() : quote.getTacos();
    List<ReorderResponse.Difference> moved = new ArrayList<>();
    int shared = Math.min(before.size(), after.size());
    for (int i = 0; i < shared; i++) {
      Taco oldLine = before.get(i);
      TacoLineResponse newLine = after.get(i);
      if (sameAmount(oldLine.getSubtotal(), newLine.getSubtotal())
          && sameAmount(oldLine.getUnitPriceAtPurchase(), newLine.getUnitPriceAtPurchase())) {
        continue;
      }
      moved.add(new ReorderResponse.Difference(oldLine.getName(), newLine.getQuantity(),
          oldLine.getUnitPriceAtPurchase(), newLine.getUnitPriceAtPurchase(),
          oldLine.getSubtotal(), newLine.getSubtotal()));
    }
    return moved;
  }

  /**
   * Whether the customer has to approve a new price. An order with no recorded
   * total predates this endpoint, so there is no baseline to disagree with and
   * nothing to confirm.
   */
  private static boolean totalsDiffer(TacoOrder source, OrderQuoteResponse quote) {
    BigDecimal previous = source.getTotal();
    return previous != null && quote.getTotal() != null
        && previous.compareTo(quote.getTotal()) != 0;
  }

  private static boolean sameAmount(BigDecimal before, BigDecimal after) {
    return before != null && after != null && before.compareTo(after) == 0;
  }

  private static Mono<TacoOrder> requireAccess(TacoOrder order, CallerIdentity caller) {
    if (caller.isAdmin()) {
      return Mono.just(order);
    }
    if (caller.getUserId() == null) {
      return Mono.error(new AuthenticationRequiredException(
          "Reordering requires an authenticated customer."));
    }
    if (order.getUser() != null && order.getUser().getId() != null
        && caller.getUserId().equals(order.getUser().getId())) {
      return Mono.just(order);
    }
    return Mono.error(new OrderAccessDeniedException());
  }

}
