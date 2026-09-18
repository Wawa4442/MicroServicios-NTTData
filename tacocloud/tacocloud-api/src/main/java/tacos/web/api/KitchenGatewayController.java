package tacos.web.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import tacos.api.dto.OrderResponse;
import tacos.data.OrderRepository;

/**
 * Gateway consumed by the kitchen application. Requires the KITCHEN role
 * (enforced by the reactive security configuration); it exposes a safe view of
 * orders without owner or payment data, exactly like the customer API.
 */
@RestController
@RequestMapping(path="/api/kitchen", produces="application/json")
public class KitchenGatewayController {

  private final OrderRepository repo;

  public KitchenGatewayController(OrderRepository repo) {
    this.repo = repo;
  }

  @GetMapping("/orders")
  public Flux<OrderResponse> recentOrders() {
    return repo.findAll().map(OrderResponse::from);
  }

}