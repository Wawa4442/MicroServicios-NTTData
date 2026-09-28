package tacos.workflow;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.OrderStatusChange;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.web.api.CallerIdentity;
import tacos.web.api.OrderAccessDeniedException;
import tacos.web.api.OrderNotFoundException;

/**
 * Central use case for every status move (TC-25).
 *
 * <p>Controllers, the kitchen queue and the event consumer all call here, so
 * the matrix, the role check and the audit entry cannot drift apart. The
 * method is a single composed publisher: load, authorize, validate, append
 * history, save. Optimistic locking comes from the {@code @Version} field on
 * {@link TacoOrder}: a stale writer fails with
 * {@code OptimisticLockingFailureException}, mapped to 409.
 */
@Service
public class OrderWorkflowService {

  static final int MAX_REASON_LENGTH = 280;

  private final OrderRepository orders;

  public OrderWorkflowService(OrderRepository orders) {
    this.orders = orders;
  }

  /**
   * Moves one order to {@code target}. A repeated transition is idempotent
   * and returns the stored order without appending history or saving.
   */
  public Mono<TacoOrder> transition(String orderId, OrderStatus target,
                                    CallerIdentity caller, String origin,
                                    String reason) {
    return Mono.defer(() -> {
      OrderStatus safeTarget = requireKnown(target);
      String safeReason = sanitizeReason(reason);
      String safeOrigin = sanitizeOrigin(origin);
      return orders.findById(orderId)
          .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
          .flatMap(order -> {
            boolean owner = isOwner(order, caller);
            if (!owner && !caller.isAdmin() && !caller.isKitchen()
                && caller.getUserId() != null) {
              return Mono.error(new OrderAccessDeniedException());
            }
            OrderStatus from = currentStatus(order);
            if (from == safeTarget) {
              return Mono.just(order);
            }
            try {
              OrderStatusPolicy.check(from, safeTarget, caller, owner);
            } catch (RuntimeException e) {
              return Mono.error(e);
            }
            OrderStatusChange change = new OrderStatusChange(from, safeTarget,
                caller.auditLabel(), new Date(), safeOrigin, safeReason);
            List<OrderStatusChange> history = order.getStatusHistory() == null
                ? new ArrayList<>()
                : new ArrayList<>(order.getStatusHistory());
            history.add(change);
            order.setStatusHistory(history);
            order.setStatus(safeTarget);
            return orders.save(order);
          });
    });
  }

  /**
   * Owner-facing cancellation: just a transition to CANCELLED through the
   * same matrix, so the "only before the kitchen starts" rule lives in one
   * place.
   */
  public Mono<TacoOrder> cancel(String orderId, CallerIdentity caller, String reason) {
    return transition(orderId, OrderStatus.CANCELLED, caller, "API", reason);
  }

  private static OrderStatus requireKnown(OrderStatus target) {
    if (target == null) {
      throw new InvalidOrderStatusException("A target status is required.");
    }
    return target;
  }

  static String sanitizeReason(String reason) {
    if (reason == null) {
      return null;
    }
    String trimmed = reason.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > MAX_REASON_LENGTH) {
      throw new InvalidOrderStatusException(
          "Reason must be at most " + MAX_REASON_LENGTH + " characters.");
    }
    return trimmed;
  }

  private static String sanitizeOrigin(String origin) {
    if (origin == null || origin.trim().isEmpty()) {
      return "API";
    }
    String trimmed = origin.trim().toUpperCase();
    return trimmed.length() > 24 ? trimmed.substring(0, 24) : trimmed;
  }

  private static OrderStatus currentStatus(TacoOrder order) {
    return order.getStatus() == null ? OrderStatus.CREATED : order.getStatus();
  }

  private static boolean isOwner(TacoOrder order, CallerIdentity caller) {
    if (caller.getUserId() == null) {
      // Legacy baseline: ownerless orders keep working for anonymous callers
      // on the old routes; the new status routes still require sign-in, which
      // the policy enforces before this flag is consulted for roles.
      return order.getUser() == null;
    }
    return order.getUser() != null
        && caller.getUserId().equals(order.getUser().getId());
  }
}
