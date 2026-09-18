package tacos.web.api;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderMapper;
import tacos.api.dto.TacoLineRequest;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.pricing.PricingService;

/**
 * Business operations for orders that must survive independent of the HTTP
 * layer: creation, partial update (PATCH), replacement (PUT) and deletion,
 * each with ingredient/payment resolution, identity, ownership and validation
 * rules.
 *
 * <p>These operations intentionally return a {@code Mono} assembled from
 * repository publishers. There is no manual {@code subscribe()} or
 * {@code block()} here: the framework owns the subscription.
 *
 * <p>The {@link OrderMapper} only transforms data that this service has
 * already resolved; repository lookups happen here, never inside the mapper.
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

  public OrderApiService(OrderRepository repo, IngredientRepository ingredientRepo,
                         UserRepository userRepo, PaymentMethodRepository paymentMethodRepo,
                         OrderMapper mapper, PricingService pricing) {
    this.repo = repo;
    this.ingredientRepo = ingredientRepo;
    this.userRepo = userRepo;
    this.paymentMethodRepo = paymentMethodRepo;
    this.mapper = mapper;
    this.pricing = pricing;
  }

  /**
   * Creates an order from the request: resolves every taco's ingredients,
   * the authenticated caller as owner and the (optional) payment method, then
   * persists the assembled {@link TacoOrder}. Server-owned fields never come
   * from the client.
   */
  public Mono<TacoOrder> createOrder(OrderCreateRequest request, CallerIdentity caller) {
    return resolveTacos(request)
        .flatMap(tacos -> resolveUser(caller)
            .flatMap(userOpt -> resolvePayment(request, caller, userOpt.orElse(null))
                .map(paymentOpt -> recalculate(mapper.toEntity(request, tacos,
                    userOpt.orElse(null), paymentOpt.orElse(null))))))
        .flatMap(repo::save);
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

  /**
   * Full replacement of the mutable business content (delivery, tacos and
   * optional payment reference) of an existing order. The path id always
   * wins: server-owned fields such as id, placedAt and the owner are
   * preserved from the stored order.
   */
  public Mono<TacoOrder> replaceOrder(String orderId, OrderCreateRequest request,
                                      CallerIdentity caller) {
    return repo.findById(orderId)
        .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
        .flatMap(existing -> requireAccess(existing, caller))
        .flatMap(existing -> resolveTacos(request)
            .flatMap(tacos -> resolveUser(caller)
                .flatMap(userOpt -> resolvePayment(request, caller, userOpt.orElse(null))
                    .map(paymentOpt -> recalculate(mapper.merge(existing, request, tacos,
                        paymentOpt.orElse(null)))))))
        .flatMap(repo::save);
  }

  /**
   * Physically removes an order. Existence and ownership are checked before
   * deleting, so a missing order surfaces as 404 and a foreign order as 403.
   * State-based cancellation is deferred to TC-25.
   */
  public Mono<Void> deleteOrder(String orderId, CallerIdentity caller) {
    return repo.findById(orderId)
        .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
        .flatMap(order -> requireAccess(order, caller))
        .flatMap(order -> repo.deleteById(order.getId()));
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
          Taco taco = new Taco();
          taco.setName(line.getName());
          taco.setIngredients(ingredients);
          return pricing.priceLine(taco, line.getQuantity());
        });
  }

  /**
   * Recomputes the server-owned money of an order from its frozen line
   * subtotals. Clients can never send these values; they always come from the
   * catalog and the coupon engine (TC-15).
   */
  private TacoOrder recalculate(TacoOrder order) {
    BigDecimal subtotal = pricing.subtotalOf(order.getTacos());
    BigDecimal discount = pricing.zero();
    order.setCurrency(pricing.currency());
    order.setSubtotal(subtotal);
    order.setDiscount(discount);
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

}