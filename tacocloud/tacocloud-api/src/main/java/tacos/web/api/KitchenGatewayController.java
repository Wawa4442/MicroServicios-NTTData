package tacos.web.api;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.api.dto.PageResponse;
import tacos.data.OrderRepository;
import tacos.kitchen.KitchenClaimRequest;
import tacos.kitchen.KitchenOrderResponse;
import tacos.kitchen.KitchenQueueService;
import tacos.messaging.OrderEventMapper;
import tacos.outbox.OutboxService;
import tacos.workflow.OrderStatusChangeRequest;
import tacos.workflow.OrderWorkflowService;

/**
 * Gateway consumed by the kitchen application (TC-26).
 *
 * <p>Requires the KITCHEN role (enforced by the reactive security
 * configuration); it exposes a safe view of orders without owner or payment
 * data, exactly like the customer API. The queue is FIFO by
 * {@code placedAt, _id} in the database, the claim is an atomic
 * {@code findAndModify} on {@code CREATED}, and status moves go through the
 * central workflow service so the kitchen cannot drift from the matrix.
 */
@RestController
@RequestMapping(path = "/api/kitchen", produces = "application/json")
public class KitchenGatewayController {

  private final OrderRepository repo;
  private final KitchenQueueService queue;
  private final OrderWorkflowService workflow;
  private final OutboxService outbox;
  private final OrderEventMapper events;
  private final CallerIdentityResolver identities;

  public KitchenGatewayController(OrderRepository repo,
                                  KitchenQueueService queue,
                                  OrderWorkflowService workflow,
                                  OutboxService outbox,
                                  OrderEventMapper events,
                                  CallerIdentityResolver identities) {
    this.repo = repo;
    this.queue = queue;
    this.workflow = workflow;
    this.outbox = outbox;
    this.events = events;
    this.identities = identities;
  }

  /**
   * Waiting tickets, oldest first.
   */
  @GetMapping("/queue")
  public Mono<PageResponse<KitchenOrderResponse>> queue(
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    return queue.queue(page, size);
  }

  /**
   * Legacy alias kept for the kitchen display written before TC-26.
   */
  @GetMapping("/orders")
  public Mono<PageResponse<KitchenOrderResponse>> recentOrders(
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    return queue.queue(page, size);
  }

  /**
   * Takes the oldest waiting ticket. Exactly one station wins even under
   * concurrency, because the claim is a single conditional write; the loser
   * gets 404 {@code kitchen_queue_empty} when nothing is left.
   */
  @PostMapping(path = "/queue/claim", consumes = "application/json")
  public Mono<KitchenOrderResponse> claim(
      @RequestBody(required = false) KitchenClaimRequest request) {
    KitchenClaimRequest body = request == null ? new KitchenClaimRequest() : request;
    return identities.identity()
        .flatMap(caller -> queue.claimNext(
            body.getStationId(), body.getCookId(), caller));
  }

  /**
   * Advances one ticket through the lifecycle as the kitchen. Ownership and
   * payment are untouched: this route can only move the status. The move is
   * registered in the outbox for reliable delivery (TC-29).
   */
  @PatchMapping(path = "/orders/{orderId}/status", consumes = "application/json")
  public Mono<KitchenOrderResponse> advance(
      @PathVariable("orderId") String orderId,
      @RequestBody @Valid OrderStatusChangeRequest request) {
    return identities.identity()
        .flatMap(caller -> repo.findById(orderId)
            .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
            .flatMap(before -> {
              OrderStatus previous = before.getStatus() == null
                  ? OrderStatus.CREATED : before.getStatus();
              return workflow.transition(
                  orderId, request.getStatus(), caller, "KITCHEN", request.getReason())
                  .flatMap(saved -> {
                    if (saved.getStatus() == previous) {
                      return Mono.just(KitchenOrderResponse.of(saved, 0));
                    }
                    return outbox.append(events.toStatusChanged(
                        saved, previous, null))
                        .thenReturn(KitchenOrderResponse.of(saved, 0));
                  });
            }));
  }
}
