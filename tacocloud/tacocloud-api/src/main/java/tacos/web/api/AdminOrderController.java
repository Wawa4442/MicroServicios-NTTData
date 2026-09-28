package tacos.web.api;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.api.dto.OrderSummaryResponse;
import tacos.api.dto.PageResponse;
import tacos.data.OrderRepository;
import tacos.paging.PageBounds;

/**
 * Operator view of every order (TC-23), under {@code /api/admin/**} which the
 * security configuration restricts to ADMIN.
 *
 * <p>It is a <em>separate</em> route, not a flag on the customer's own history.
 * A hidden {@code ?all=true} on a "me" endpoint is one copy-paste away from
 * leaking every customer's orders into the wrong client; a different URL with a
 * different authorization rule is much harder to get wrong, and it also makes
 * the "cross-customer" read greppable.
 */
@RestController
@RequestMapping(path = "/api/admin/orders", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class AdminOrderController {

  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;

  private final OrderRepository orders;

  public AdminOrderController(OrderRepository orders) {
    this.orders = orders;
  }

  /**
   * @param userId optional filter, for narrowing to one customer. It is an
   *               operator filter and not an ownership claim, which is exactly
   *               why it lives here and not on the "me" routes.
   */
  @GetMapping
  public Mono<PageResponse<OrderSummaryResponse>> allOrders(
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size,
      @RequestParam(name = "userId", required = false) String userId) {
    PageBounds bounds = PageBounds.of(page, size, DEFAULT_SIZE, MAX_SIZE);
    PageRequest window = PageRequest.of(bounds.getPage(), bounds.getSize());
    String owner = userId == null || userId.trim().isEmpty() ? null : userId.trim();
    Mono<Long> total = owner == null ? orders.count() : orders.countByUser_Id(owner);
    Mono<List<TacoOrder>> rows = owner == null
        ? orders.findAllNewestFirst(window).collectList()
        : orders.findByUser_IdOrderByPlacedAtDescIdDesc(owner, window).collectList();
    return Mono.zip(total, rows)
        .map(tally -> PageResponse.of(summaries(tally.getT2()),
            bounds.getPage(), bounds.getSize(), tally.getT1()));
  }

  private static List<OrderSummaryResponse> summaries(List<TacoOrder> orders) {
    return orders.stream()
        .map(OrderSummaryResponse::from)
        .collect(Collectors.toList());
  }

}
