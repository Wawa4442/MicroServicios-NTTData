package tacos.history;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.OrderSummaryResponse;
import tacos.api.dto.PageResponse;
import tacos.data.OrderRepository;
import tacos.web.api.OrderNotFoundException;

/**
 * Private order history (TC-23).
 *
 * <p>Every read is scoped to one customer <em>in the query</em>, never by
 * loading orders and filtering them in Java. That is not a style preference: a
 * global page filtered afterwards would be both wrong (it leaks the existence
 * of other people's orders into the page size) and slow (it drags the whole
 * collection through the heap to show twenty rows).
 *
 * <p>Ordering is {@code placedAt desc, _id desc}. The secondary key is what
 * makes paging reproducible — two orders placed in the same millisecond would
 * otherwise be free to swap places, and the customer would see one of them on
 * page 1 and page 2.
 */
@Service
public class OrderHistoryService {

  private final OrderRepository orders;

  public OrderHistoryService(OrderRepository orders) {
    this.orders = orders;
  }

  public Mono<PageResponse<OrderSummaryResponse>> history(String userId, int page, int size) {
    return Mono.zip(
        orders.countByUser_Id(userId),
        orders.findByUser_IdOrderByPlacedAtDescIdDesc(userId,
            PageRequest.of(page, size)).collectList())
        .map(tally -> PageResponse.of(summaries(tally.getT2()), page, size, tally.getT1()));
  }

  /**
   * The detail of one order, only if it is the caller's.
   *
   * <p>An order that belongs to somebody else is reported exactly like an order
   * that does not exist: 404 {@code order_not_found}. A 403 would confirm the
   * order is real, which turns this endpoint into an oracle for guessing which
   * order ids exist. The mutation routes under {@code /api/orders} keep their
   * 403, because there the operator already knows the order exists.
   */
  public Mono<OrderResponse> detail(String userId, String orderId) {
    return orders.findById(orderId)
        .filter(order -> ownedBy(order, userId))
        .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
        .map(OrderResponse::from);
  }

  private static boolean ownedBy(TacoOrder order, String userId) {
    return order.getUser() != null && order.getUser().getId() != null
        && order.getUser().getId().equals(userId);
  }

  private static List<OrderSummaryResponse> summaries(List<TacoOrder> orders) {
    return orders.stream()
        .map(OrderSummaryResponse::from)
        .collect(java.util.stream.Collectors.toList());
  }

}
