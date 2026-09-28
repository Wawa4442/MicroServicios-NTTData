package tacos.web.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderMapper;
import tacos.api.dto.OrderQuoteResponse;
import tacos.api.dto.OrderResponse;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEventMapper;
import tacos.outbox.OrderPlacementService;
import tacos.outbox.OutboxService;
import tacos.workflow.OrderCancelRequest;
import tacos.workflow.OrderStatusChangeRequest;
import tacos.workflow.OrderWorkflowService;

@RestController
@RequestMapping(path="/api/orders",
                produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class OrderApiController {

  private static final Set<String> PATCHABLE_FIELDS = Set.of(
      "deliveryName", "deliveryStreet", "deliveryCity",
      "deliveryState", "deliveryZip");

  private final OrderRepository repo;
  private final EmailOrderService emailOrderService;
  private final OrderApiService orderService;
  private final OrderWorkflowService workflow;
  private final OrderPlacementService placement;
  private final OutboxService outbox;
  private final OrderEventMapper events;
  private final OrderMapper orderMapper;
  private final ObjectMapper objectMapper;
  private final CallerIdentityResolver identities;

  public OrderApiController(OrderRepository repo,
                            EmailOrderService emailOrderService,
                            OrderApiService orderService,
                            OrderWorkflowService workflow,
                            OrderPlacementService placement,
                            OutboxService outbox,
                            OrderEventMapper events,
                            OrderMapper orderMapper,
                            ObjectMapper objectMapper,
                            CallerIdentityResolver identities) {
    this.repo = repo;
    this.emailOrderService = emailOrderService;
    this.orderService = orderService;
    this.workflow = workflow;
    this.placement = placement;
    this.outbox = outbox;
    this.events = events;
    this.orderMapper = orderMapper;
    this.objectMapper = objectMapper;
    this.identities = identities;
  }

  /**
   * There is deliberately no {@code GET /api/orders} listing every order here
   * (TC-23). A global dump of other customers' orders was the default read of
   * this resource, and replacing it with a "me" route plus an explicitly
   * operator-only one is what makes the privacy property testable.
   */
  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrder(
      @RequestBody @Valid OrderCreateRequest request,
      @RequestHeader(name = "X-Correlation-ID", required = false) String correlationId) {
    String correlation = correlationId == null || correlationId.trim().isEmpty()
        ? UUID.randomUUID().toString() : correlationId.trim();
    return caller()
        .flatMap(caller -> placement.placeOrder(request, caller, correlation))
        .map(OrderResponse::from);
  }

  @PostMapping(path="fromEmail", consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrderFromEmail(
      @RequestBody Mono<EmailOrder> emailOrder,
      @RequestHeader(name = "X-Correlation-ID", required = false) String correlationId) {
    String correlation = correlationId == null || correlationId.trim().isEmpty()
        ? UUID.randomUUID().toString() : correlationId.trim();
    return emailOrderService.convertEmailOrderToDomainOrder(emailOrder)
        .map(order -> {
          order.setReservationKey(UUID.randomUUID().toString());
          if (order.getStatus() == null) {
            order.setStatus(OrderStatus.CREATED);
          }
          return order;
        })
        .flatMap(orderService::persistAssembledOrder)
        .flatMap(saved -> outbox.append(events.toCreated(saved, correlation))
            .thenReturn(saved))
        .map(OrderResponse::from);
  }

  @PostMapping(path="quote", consumes="application/json")
  public Mono<OrderQuoteResponse> quoteOrder(
      @RequestBody @Valid OrderCreateRequest request) {
    return caller()
        .flatMap(caller -> orderService.quoteOrder(request, caller));
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
   * Advances the lifecycle of one order (TC-25). The matrix and the role
   * check live in the workflow service; here we only resolve the caller and
   * translate the body. A repeated transition is idempotent (200 with the
   * stored order); an illegal jump is 409; a move the caller may not run is
   * 401/403. The resulting event is registered in the outbox (TC-29) for
   * reliable delivery instead of being sent inline.
   */
  @PatchMapping(path = "/{orderId}/status", consumes = "application/json")
  public Mono<ResponseEntity<OrderResponse>> changeStatus(
      @PathVariable("orderId") String orderId,
      @RequestBody @Valid OrderStatusChangeRequest request,
      @RequestHeader(name = "X-Correlation-ID", required = false) String correlationId) {
    String correlation = correlationId == null || correlationId.trim().isEmpty()
        ? UUID.randomUUID().toString() : correlationId.trim();
    return caller()
        .flatMap(caller -> repo.findById(orderId)
            .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
            .flatMap(before -> {
              OrderStatus previous = before.getStatus() == null
                  ? OrderStatus.CREATED : before.getStatus();
              return workflow.transition(
                  orderId, request.getStatus(), caller, "API", request.getReason())
                  .flatMap(saved -> {
                    if (saved.getStatus() == previous) {
                      return Mono.just(saved);
                    }
                    return outbox.append(events.toStatusChanged(
                        saved, previous, correlation)).thenReturn(saved);
                  });
            }))
        .map(saved -> ResponseEntity.ok(OrderResponse.from(saved)));
  }

  /**
   * Owner-facing cancellation (TC-25): always targets CANCELLED through the
   * same matrix, so "only while the kitchen has not started" is enforced in
   * one place.
   */
  @PostMapping(path = "/{orderId}/cancel", consumes = "application/json")
  public Mono<ResponseEntity<OrderResponse>> cancelOrder(
      @PathVariable("orderId") String orderId,
      @RequestBody(required = false) OrderCancelRequest request,
      @RequestHeader(name = "X-Correlation-ID", required = false) String correlationId) {
    String reason = request == null ? null : request.getReason();
    String correlation = correlationId == null || correlationId.trim().isEmpty()
        ? UUID.randomUUID().toString() : correlationId.trim();
    return caller()
        .flatMap(caller -> repo.findById(orderId)
            .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
            .flatMap(before -> {
              OrderStatus previous = before.getStatus() == null
                  ? OrderStatus.CREATED : before.getStatus();
              return workflow.cancel(orderId, caller, reason)
                  .flatMap(saved -> {
                    if (saved.getStatus() == previous) {
                      return Mono.just(saved);
                    }
                    return outbox.append(events.toCancelled(
                        saved, previous, correlation)).thenReturn(saved);
                  });
            }))
        .map(saved -> ResponseEntity.ok(OrderResponse.from(saved)));
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
    return identities.identity();
  }

}
