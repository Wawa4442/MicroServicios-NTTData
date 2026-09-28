package tacos.workflow;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import tacos.OrderStatus;
import tacos.web.api.CallerIdentity;

/**
 * The only place that knows which status moves are legal and who may request
 * them (TC-25).
 *
 * <p>The happy path is {@code CREATED -> ACCEPTED -> PREPARING -> READY ->
 * OUT_FOR_DELIVERY -> DELIVERED}. Cancellation is allowed from
 * {@code CREATED} and {@code ACCEPTED} by the owner, and from anywhere before
 * {@code DELIVERED} by an operator. A repeated transition (asking for the
 * status the order already has) is idempotent: it succeeds without writing a
 * new history entry.
 *
 * <p>Role rules, in one place so controllers and listeners cannot drift:
 * <ul>
 *   <li>customers (USER) may only cancel their own early orders;</li>
 *   <li>the kitchen (KITCHEN) moves orders forward but never touches
 *       ownership, payment or delivery content;</li>
 *   <li>operators (ADMIN) may run any legal move.</li>
 * </ul>
 */
public final class OrderStatusPolicy {

  private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED;

  static {
    Map<OrderStatus, Set<OrderStatus>> moves = new EnumMap<>(OrderStatus.class);
    moves.put(OrderStatus.CREATED,
        EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.CANCELLED));
    moves.put(OrderStatus.ACCEPTED,
        EnumSet.of(OrderStatus.PREPARING, OrderStatus.CANCELLED));
    moves.put(OrderStatus.PREPARING,
        EnumSet.of(OrderStatus.READY, OrderStatus.CANCELLED));
    moves.put(OrderStatus.READY,
        EnumSet.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CANCELLED));
    moves.put(OrderStatus.OUT_FOR_DELIVERY,
        EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED));
    moves.put(OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class));
    moves.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    ALLOWED = Collections.unmodifiableMap(moves);
  }

  private OrderStatusPolicy() {
  }

  public static Set<OrderStatus> allowedFrom(OrderStatus from) {
    return ALLOWED.getOrDefault(from, Set.of());
  }

  public static boolean isAllowed(OrderStatus from, OrderStatus to) {
    return allowedFrom(from).contains(to);
  }

  /**
   * @param isOwner true when the caller owns the order (or the order has no
   *                owner and the caller is anonymous in the legacy baseline).
   * @throws OrderStatusTransitionException when the move itself is illegal.
   * @throws tacos.web.api.OrderAccessDeniedException when the caller role may
   *                not run this move.
   * @throws tacos.web.api.AuthenticationRequiredException when nobody is
   *                signed in.
   */
  public static void check(OrderStatus from, OrderStatus to,
                           CallerIdentity caller, boolean isOwner) {
    if (from == to) {
      return;
    }
    if (!isAllowed(from, to)) {
      throw new OrderStatusTransitionException(
          "Transition " + from + " -> " + to + " is not allowed.");
    }
    if (caller.getUserId() == null && !caller.isAdmin() && !caller.isKitchen()) {
      throw new tacos.web.api.AuthenticationRequiredException(
          "Sign in to change the status of an order.");
    }
    if (caller.isAdmin()) {
      return;
    }
    boolean toCancelled = to == OrderStatus.CANCELLED;
    if (toCancelled) {
      // Owners may cancel early; the kitchen never cancels, operators do.
      if (isOwner && (from == OrderStatus.CREATED || from == OrderStatus.ACCEPTED)) {
        return;
      }
      throw new tacos.web.api.OrderAccessDeniedException();
    }
    // Forward moves belong to the kitchen (or an operator, handled above).
    // A plain customer cannot mark anything delivered, preparing, etc.
    if (caller.isKitchen()) {
      return;
    }
    throw new tacos.web.api.OrderAccessDeniedException();
  }

  /**
   * Whether the owner may still cancel from this status without an operator.
   */
  public static boolean ownerMayCancel(OrderStatus from) {
    return from == OrderStatus.CREATED || from == OrderStatus.ACCEPTED;
  }
}
