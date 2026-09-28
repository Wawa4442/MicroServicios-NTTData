package tacos.web.api;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderStatus;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderMapper;
import tacos.api.dto.OrderQuoteResponse;
import tacos.api.dto.TacoLineRequest;
import tacos.coupon.CouponDecision;
import tacos.coupon.CouponEngine;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.inventory.InventoryService;
import tacos.inventory.ReservationStatus;
import tacos.inventory.StockReservation;
import tacos.pricing.PricingService;
import tacos.rules.TacoValidator;

/**
 * Business operations for orders that must survive independent of the HTTP
 * layer: creation, quote, partial update (PATCH), replacement (PUT) and
 * deletion.
 *
 * <p>The flow is a single composed publisher per use case:
 *
 * <pre>
 *   validate design (TC-18) → resolve ingredients → price (TC-14)
 *     → apply coupon (TC-15) → reserve inventory (TC-16) → save → confirm
 * </pre>
 *
 * Quote stops before the reserve/save; create and email persist go all the
 * way, and any failure after a partial reservation compensates it. There is no
 * manual {@code subscribe()} or {@code block()} anywhere.
 */
@Service
public class OrderApiService {

  private static final String US_STATE_PATTERN = "[A-Z]{2}";
  private static final String US_ZIP_PATTERN = "\\d{5}";

  private final OrderRepository repo;
  private final IngredientRepository ingredientRepo;
  private final UserRepository userRepo;
  private final PaymentMethodRepository paymentMethodRepo;
  private final OrderMapper mapper;
  private final PricingService pricing;
  private final CouponEngine coupons;
  private final TacoValidator validator;
  private final InventoryService inventory;

  public OrderApiService(OrderRepository repo, IngredientRepository ingredientRepo,
                         UserRepository userRepo, PaymentMethodRepository paymentMethodRepo,
                         OrderMapper mapper, PricingService pricing,
                         CouponEngine coupons, TacoValidator validator,
                         InventoryService inventory) {
    this.repo = repo;
    this.ingredientRepo = ingredientRepo;
    this.userRepo = userRepo;
    this.paymentMethodRepo = paymentMethodRepo;
    this.mapper = mapper;
    this.pricing = pricing;
    this.coupons = coupons;
    this.validator = validator;
    this.inventory = inventory;
  }

  /**
   * Creates an order: resolves ingredients, validates the design, prices the
   * lines, applies the optional coupon, then reserves stock before persisting.
   * Server-owned fields never come from the client.
   */
  public Mono<TacoOrder> createOrder(OrderCreateRequest request, CallerIdentity caller) {
    return buildOrder(request, caller)
        .flatMap(this::persistAssembledOrder);
  }

  /**
   * Quotes the same server-side money without persisting or reserving stock
   * (TC-14/TC-15). Any design or coupon problem surfaces here too, because the
   * same rules run before the quote.
   */
  public Mono<OrderQuoteResponse> quoteOrder(OrderCreateRequest request, CallerIdentity caller) {
    return buildOrder(request, caller).map(OrderQuoteResponse::of);
  }

  /**
   * Full replacement of the mutable business content (delivery, tacos and the
   * optional payment reference) of an existing order. The path id wins:
   * server-owned fields are preserved from the stored order. The new content
   * is reserved with a fresh key, and once it is persisted the previous
   * reservation is released so the old stock is returned.
   */
  public Mono<TacoOrder> replaceOrder(String orderId, OrderCreateRequest request,
                                      CallerIdentity caller) {
    return repo.findById(orderId)
        .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
        .flatMap(existing -> requireAccess(existing, caller)
            .flatMap(existingAccessible -> buildReplacement(existingAccessible,
                request, caller)
                .flatMap(this::persistAssembledOrder)
                .flatMap(saved -> releaseStaleReservation(existingAccessible, saved))));
  }

  /**
   * After a replacement is persisted, returns the stock still held by the
   * previous reservation (if any and if it differs from the new one).
   */
  private Mono<TacoOrder> releaseStaleReservation(TacoOrder previous, TacoOrder saved) {
    if (previous.getReservationKey() != null
        && !previous.getReservationKey().equals(saved.getReservationKey())) {
      return inventory.release(previous.getReservationKey()).thenReturn(saved);
    }
    return Mono.just(saved);
  }

  /**
   * Removes an order physically and releases its reservation. Existence and
   * ownership are checked first; an order that the kitchen already started
   * (PREPARING or later, except CANCELLED) is rejected with 409 so it is
   * cancelled through the lifecycle instead of vanishing (TC-25).
   */
  public Mono<Void> deleteOrder(String orderId, CallerIdentity caller) {
    return repo.findById(orderId)
        .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
        .flatMap(order -> requireAccess(order, caller))
        .flatMap(order -> {
          OrderStatus status = order.getStatus() == null
              ? OrderStatus.CREATED : order.getStatus();
          if (status == OrderStatus.PREPARING || status == OrderStatus.READY
              || status == OrderStatus.OUT_FOR_DELIVERY
              || status == OrderStatus.DELIVERED) {
            return Mono.error(new tacos.workflow.OrderStatusTransitionException(
                "Order in status " + status + " cannot be deleted; cancel it instead."));
          }
          return inventory.releaseForOrder(order.getId())
              .then(repo.deleteById(order.getId()));
        });
  }

  /**
   * Persists an already-assembled order atomically with respect to inventory:
   * reserve first (idempotent on the reservation key), save, confirm. Any
   * failure after a partial reservation compensates it; a confirmed
   * reservation replays its own order instead of debiting twice.
   */
  public Mono<TacoOrder> persistAssembledOrder(TacoOrder order) {
    return inventory.reserve(order.getReservationKey(),
            InventoryService.requirementsOf(order))
        .flatMap(reservation -> {
          if (reservation.getStatus() == ReservationStatus.CONFIRMED
              && reservation.getOrderId() != null) {
            return repo.findById(reservation.getOrderId())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "Confirmed reservation " + order.getReservationKey()
                        + " has no matching order.")));
          }
          return repo.save(order)
              .flatMap(saved -> inventory.confirm(order.getReservationKey(), saved.getId())
                  .thenReturn(saved));
        })
        .onErrorResume(error -> inventory.release(order.getReservationKey())
            .then(Mono.error(error)));
  }

  /**
   * Applies only the delivery fields present in the patch. The DTO already
   * caps which properties can reach this method; identity, payment, user,
   * totals and tacos are never touched here.
   */
  public Mono<TacoOrder> patchOrder(String orderId, OrderPatchRequest patch,
                                    CallerIdentity caller) {
    return repo.findById(orderId)
        .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
        .flatMap(order -> requireAccess(order, caller))
        .flatMap(order -> applyPatch(order, patch))
        .flatMap(repo::save);
  }

  private Mono<TacoOrder> buildOrder(OrderCreateRequest request, CallerIdentity caller) {
    return resolveTacos(request)
        .flatMap(tacos -> resolveUser(caller)
            .flatMap(userOpt -> resolvePayment(request, caller, userOpt.orElse(null))
                .map(paymentOpt -> {
                  TacoOrder order = mapper.toEntity(request, tacos,
                      userOpt.orElse(null), paymentOpt.orElse(null));
                  order.setReservationKey(idempotencyKeyOf(request));
                  return recalculate(order, request.getCouponCode());
                })));
  }

  private Mono<TacoOrder> buildReplacement(TacoOrder existing,
      OrderCreateRequest request, CallerIdentity caller) {
    return resolveTacos(request)
        .flatMap(tacos -> resolveUser(caller)
            .flatMap(userOpt -> resolvePayment(request, caller, userOpt.orElse(null))
                .map(paymentOpt -> {
                  TacoOrder order = mapper.merge(existing, request, tacos,
                      paymentOpt.orElse(null));
                  order.setReservationKey(UUID.randomUUID().toString());
                  return recalculate(order, request.getCouponCode());
                })));
  }

  private Mono<TacoOrder> requireAccess(TacoOrder order, CallerIdentity caller) {
    if (caller.isAdmin()) {
      return Mono.just(order);
    }
    if (caller.getUserId() == null) {
      // No authentication yet in this baseline (TC-11 closes this). Anonymous
      // requests keep working as before; the moment a user is present, the
      // ownership rule below applies.
      return Mono.just(order);
    }
    if (order.getUser() != null
        && caller.getUserId().equals(order.getUser().getId())) {
      return Mono.just(order);
    }
    return Mono.error(new OrderAccessDeniedException());
  }

  private Mono<List<Taco>> resolveTacos(OrderCreateRequest request) {
    List<TacoLineRequest> lines = request.getTacos() == null
        ? Collections.emptyList() : request.getTacos();
    return Flux.fromIterable(lines)
        .concatMap(this::resolveTaco)
        .collectList();
  }

  private Mono<Taco> resolveTaco(TacoLineRequest line) {
    List<String> ids = line.getIngredientIds() == null
        ? Collections.emptyList() : line.getIngredientIds();
    return Flux.fromIterable(ids)
        .concatMap(id -> ingredientRepo.<Ingredient>findById(id)
            .switchIfEmpty(Mono.error(new UnknownIngredientException(id))))
        .collectList()
        .map(ingredients -> {
          validator.validateOrThrow(ingredients);
          Taco taco = new Taco();
          taco.setName(line.getName());
          taco.setIngredients(ingredients);
          return pricing.priceLine(taco, line.getQuantity());
        });
  }

  /**
   * Recomputes the server-owned money of an order from its frozen line
   * subtotals and the coupon engine. Clients can never send these values; they
   * always come from the catalog and the promotions (TC-14/TC-15). A coupon
   * that cannot be applied rejects the order: silently dropping the code would
   * lose honest money.
   */
  private TacoOrder recalculate(TacoOrder order, String rawCoupon) {
    BigDecimal subtotal = pricing.subtotalOf(order.getTacos());
    BigDecimal discount = pricing.zero();
    String couponCode = null;
    if (rawCoupon != null && !rawCoupon.trim().isEmpty()) {
      CouponDecision decision = coupons.apply(subtotal, rawCoupon);
      discount = decision.getDiscount();
      couponCode = decision.getNormalizedCode();
    }
    order.setCurrency(pricing.currency());
    order.setSubtotal(subtotal);
    order.setDiscount(discount);
    order.setCouponCode(couponCode);
    order.setTotal(subtotal.subtract(discount));
    return order;
  }

  private Mono<Optional<User>> resolveUser(CallerIdentity caller) {
    if (caller.getUserId() == null) {
      return Mono.just(Optional.empty());
    }
    return userRepo.findById(caller.getUserId())
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<PaymentMethod>> resolvePayment(OrderCreateRequest request,
                                                       CallerIdentity caller, User user) {
    String paymentMethodId = request.getPaymentMethodId();
    if (paymentMethodId == null || paymentMethodId.trim().isEmpty()) {
      return Mono.just(Optional.empty());
    }
    return paymentMethodRepo.findById(paymentMethodId)
        .switchIfEmpty(Mono.error(new UnknownPaymentMethodException(paymentMethodId)))
        .map(payment -> {
          if (user != null && payment.getUser() != null
              && !user.getId().equals(payment.getUser().getId())) {
            throw new OrderAccessDeniedException();
          }
          return payment;
        })
        .map(Optional::of);
  }

  private Mono<TacoOrder> applyPatch(TacoOrder order, OrderPatchRequest patch) {
    if (patch.getDeliveryState() != null
        && !patch.getDeliveryState().matches(US_STATE_PATTERN)) {
      return Mono.error(new OrderPatchValidationException(
          "deliveryState must be a two-letter state code"));
    }
    if (patch.getDeliveryZip() != null
        && !patch.getDeliveryZip().matches(US_ZIP_PATTERN)) {
      return Mono.error(new OrderPatchValidationException(
          "deliveryZip must be a five-digit postal code"));
    }
    if (patch.getDeliveryName() != null) {
      order.setDeliveryName(patch.getDeliveryName());
    }
    if (patch.getDeliveryStreet() != null) {
      order.setDeliveryStreet(patch.getDeliveryStreet());
    }
    if (patch.getDeliveryCity() != null) {
      order.setDeliveryCity(patch.getDeliveryCity());
    }
    if (patch.getDeliveryState() != null) {
      order.setDeliveryState(patch.getDeliveryState());
    }
    if (patch.getDeliveryZip() != null) {
      order.setDeliveryZip(patch.getDeliveryZip());
    }
    return Mono.just(order);
  }

  private static String idempotencyKeyOf(OrderCreateRequest request) {
    String key = request.getIdempotencyKey();
    if (key == null || key.trim().isEmpty()) {
      return UUID.randomUUID().toString();
    }
    return key.trim();
  }

}