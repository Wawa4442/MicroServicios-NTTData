package tacos.web.api;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.OrderSummaryResponse;
import tacos.api.dto.PageResponse;
import tacos.history.OrderHistoryProperties;
import tacos.history.OrderHistoryService;
import tacos.paging.PageBounds;

/**
 * {@code /api/users/me/orders} (TC-23): the customer's own history, newest
 * first.
 *
 * <p>The {@code me} in the path is the entire access control story. There is no
 * {@code userId} parameter to validate, forget or inject, and the operator's
 * view of everybody's orders is a separate, explicitly ADMIN-only route instead
 * of a flag on this one.
 */
@RestController
@RequestMapping(path = "/api/users/me/orders", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class OrderHistoryController {

  private final OrderHistoryService history;
  private final CallerIdentityResolver identities;
  private final OrderHistoryProperties properties;

  public OrderHistoryController(OrderHistoryService history, CallerIdentityResolver identities,
      OrderHistoryProperties properties) {
    this.history = history;
    this.identities = identities;
    this.properties = properties;
  }

  @GetMapping
  public Mono<PageResponse<OrderSummaryResponse>> myOrders(
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    PageBounds bounds = PageBounds.of(page, size,
        properties.getDefaultSize(), properties.getMaxSize());
    return identities.requiredUserId()
        .flatMap(userId -> history.history(userId, bounds.getPage(), bounds.getSize()));
  }

  @GetMapping("/{orderId}")
  public Mono<OrderResponse> myOrder(@PathVariable("orderId") String orderId) {
    return identities.requiredUserId()
        .flatMap(userId -> history.detail(userId, orderId));
  }

}
