package tacos.web.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.User;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderMapper;
import tacos.api.dto.OrderResponse;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@RestController
@RequestMapping(path="/api/orders",
                produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class OrderApiController {

  private static final Set<String> PATCHABLE_FIELDS = Set.of(
      "deliveryName", "deliveryStreet", "deliveryCity",
      "deliveryState", "deliveryZip");

  private final OrderRepository repo;
  private final OrderMessagingService orderMessages;
  private final EmailOrderService emailOrderService;
  private final OrderApiService orderService;
  private final OrderMapper orderMapper;
  private final ObjectMapper objectMapper;

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            OrderApiService orderService,
                            OrderMapper orderMapper,
                            ObjectMapper objectMapper) {
    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
    this.orderService = orderService;
    this.orderMapper = orderMapper;
    this.objectMapper = objectMapper;
  }

  @GetMapping(produces="application/json")
  public Flux<OrderResponse> allOrders() {
    return repo.findAll().map(OrderResponse::from);
  }

  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrder(
      @RequestBody @Valid OrderCreateRequest request) {
    return caller()
        .flatMap(caller -> orderService.createOrder(request, caller))
        .flatMap(saved -> orderMessages.sendOrderReactive(saved).thenReturn(saved))
        .map(OrderResponse::from);
  }

  @PostMapping(path="fromEmail", consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
    return emailOrderService.convertEmailOrderToDomainOrder(emailOrder)
        .flatMap(repo::save)
        .flatMap(saved -> orderMessages.sendOrderReactive(saved).thenReturn(saved))
        .map(OrderResponse::from);
  }

  @PatchMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>> patchOrder(
      @PathVariable("orderId") String orderId,
      @RequestBody(required=false) JsonNode patchNode) {

    if (patchNode == null || !patchNode.isObject()) {
      throw new OrderPatchValidationException("The patch must be a JSON object.");
    }
    return parsePatch(patchNode)
        .flatMap(patch -> caller()
            .flatMap(caller -> orderService.patchOrder(orderId, patch, caller)))
        .map(saved -> ResponseEntity.ok(OrderResponse.from(saved)));
  }

  @PutMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>> putOrder(
      @PathVariable("orderId") String orderId,
      @RequestBody @Valid OrderCreateRequest request) {
    return caller()
        .flatMap(caller -> orderService.replaceOrder(orderId, request, caller))
        .map(saved -> ResponseEntity.ok(OrderResponse.from(saved)));
  }

  @DeleteMapping("/{orderId}")
  public Mono<ResponseEntity<Void>> deleteOrder(
      @PathVariable("orderId") String orderId) {
    return caller()
        .flatMap(caller -> orderService.deleteOrder(orderId, caller))
        .then(Mono.just(ResponseEntity.noContent().<Void>build()));
  }

  /**
   * Enforces the patch whitelist before any repository call: any field that is
   * not a delivery field is rejected explicitly (400). Identity, payment,
   * totals, tacos and the user are therefore impossible to touch through PATCH.
   */
  private Mono<OrderPatchRequest> parsePatch(JsonNode patchNode) {
    return Mono.fromSupplier(() -> {
      List<String> forbidden = new ArrayList<>();
      patchNode.fieldNames().forEachRemaining(name -> {
        if (!PATCHABLE_FIELDS.contains(name)) {
          forbidden.add(name);
        }
      });
      if (!forbidden.isEmpty()) {
        throw new OrderPatchValidationException(
            "Fields are not patchable: " + String.join(", ", forbidden));
      }
      return objectMapper.convertValue(patchNode, OrderPatchRequest.class);
    });
  }

  /**
   * Resolves the caller from the reactive security context. Anonymous
   * (no authentication present) is the current baseline posture; ownership and
   * authorization rules are enforced by the service and by TC-11 security.
   */
  private Mono<CallerIdentity> caller() {
    return ReactiveSecurityContextHolder.getContext()
        .map(ctx -> ctx.getAuthentication())
        .filter(auth -> auth != null && auth.isAuthenticated())
        .map(Authentication::getPrincipal)
        .map(this::toCallerIdentity)
        .defaultIfEmpty(CallerIdentity.anonymous());
  }

  private CallerIdentity toCallerIdentity(Object principal) {
    if (principal instanceof User) {
      User user = (User) principal;
      boolean admin = user.getAuthorities().stream()
          .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
      return admin ? CallerIdentity.admin() : CallerIdentity.user(user.getId());
    }
    return CallerIdentity.anonymous();
  }

}