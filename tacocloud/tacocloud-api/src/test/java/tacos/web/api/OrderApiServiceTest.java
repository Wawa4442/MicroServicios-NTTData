package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderMapper;
import tacos.api.dto.OrderQuoteResponse;
import tacos.api.dto.TacoLineRequest;
import tacos.coupon.CouponEngine;
import tacos.coupon.CouponNotApplicableException;
import tacos.coupon.CouponProperties;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.inventory.InventoryService;
import tacos.inventory.StockReservation;
import tacos.pricing.PricingProperties;
import tacos.pricing.PricingService;
import tacos.rules.AvailableIngredientsRule;
import tacos.rules.GhostPepperNeedsDrinkRule;
import tacos.rules.IngredientCountRule;
import tacos.rules.NoDuplicateIngredientRule;
import tacos.rules.SingleBaseRule;
import tacos.rules.TacoPhysicsProperties;
import tacos.rules.TacoValidator;
import tacos.rules.VeganNoMeatRule;

/**
 * TC-16/TC-18 service integration: the REAL Taco Physics validator and the
 * REAL pricing engine run inside {@code OrderApiService}, proving that a
 * design is rejected (or a coupon denied) BEFORE any inventory reservation and
 * BEFORE any persistence. Recommend the quote stops before the side effects.
 */
public class OrderApiServiceTest {

  private static final Ingredient FLTO = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
  private static final Ingredient GRBF = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN);

  private OrderRepository repo;
  private IngredientRepository ingredientRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private UserRepository userRepo;
  private InventoryService inventory;
  private OrderApiService service;

  @BeforeEach
  public void setup() {
    repo = mock(OrderRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    paymentMethodRepo = mock(PaymentMethodRepository.class);
    userRepo = mock(UserRepository.class);
    inventory = mock(InventoryService.class);
    when(ingredientRepo.<Ingredient>findById("FLTO")).thenReturn(Mono.just(FLTO));
    when(ingredientRepo.<Ingredient>findById("GRBF")).thenReturn(Mono.just(GRBF));
    when(inventory.reserve(anyString(), anyList())).thenReturn(Mono.just(
        StockReservation.created("k", List.of(tacos.inventory.ReservedItem.of("FLTO", 1)))));
    when(inventory.confirm(anyString(), anyString())).thenReturn(Mono.empty());
    when(inventory.release(anyString())).thenReturn(Mono.empty());
    service = new OrderApiService(repo, ingredientRepo, userRepo, paymentMethodRepo,
        new OrderMapper(), new PricingService(new PricingProperties()),
        new CouponEngine(new CouponProperties(), java.time.Clock.systemUTC()),
        realValidator(), inventory);
  }

  @Test
  public void invalidDesign_isRejectedBeforeAnyReservation() {
    when(repo.save(any(TacoOrder.class))).thenAnswer(idAssigningSave());
    OrderCreateRequest request = request("ghost", couponTaco("FLTO"));

    StepVerifier.create(service.createOrder(request, CallerIdentity.anonymous()))
        .expectErrorSatisfies(error ->
            assertTrue(error instanceof tacos.rules.TacoDesignInvalidException,
                "expected a design violation"))
        .verify();

    verify(inventory, never()).reserve(anyString(), anyList());
    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void appliedCoupon_unknownCode_rejectsBeforeAnyReservation() {
    when(repo.save(any(TacoOrder.class))).thenAnswer(idAssigningSave());
    OrderCreateRequest request = request("welcome10", validTaco());

    StepVerifier.create(service.createOrder(request, CallerIdentity.anonymous()))
        .expectErrorSatisfies(error ->
            assertTrue(error instanceof CouponNotApplicableException,
                "expected coupon rejection"))
        .verify();

    verify(inventory, never()).reserve(anyString(), anyList());
    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void validDesign_reservesThenSavesThenConfirms_inOrder() {
    when(repo.save(any(TacoOrder.class))).thenAnswer(idAssigningSave());
    OrderCreateRequest request = request(null, validTaco());

    StepVerifier.create(service.createOrder(request, CallerIdentity.anonymous()))
        .assertNext(order -> {
          assertEquals("USD", order.getCurrency());
          assertEquals(0, new java.math.BigDecimal("1.00").compareTo(order.getSubtotal()),
              "one 1.00 base-fee taco line");
          assertEquals(0, new java.math.BigDecimal("1.00").compareTo(order.getTotal()));
          assertTrue(order.getReservationKey() != null);
        })
        .verifyComplete();

    InOrder ordered = inOrder(inventory, repo);
    ordered.verify(inventory).reserve(anyString(), anyList());
    ordered.verify(repo).save(any(TacoOrder.class));
    ordered.verify(inventory).confirm(anyString(), anyString());
    verify(inventory, never()).release(anyString());
    verify(repo, never()).deleteById(anyString());
  }

  @Test
  public void quote_stopsBeforePersistenceAndReservation() {
    OrderCreateRequest request = request(null, validTaco());

    StepVerifier.create(service.quoteOrder(request, CallerIdentity.anonymous()))
        .assertNext(quote -> {
          assertEquals(0, new java.math.BigDecimal("1.00").compareTo(quote.getSubtotal()));
          assertEquals(0, ZERO.compareTo(quote.getDiscount()));
          assertEquals(0, new java.math.BigDecimal("1.00").compareTo(quote.getTotal()));
        })
        .verifyComplete();

    verify(repo, never()).save(any(TacoOrder.class));
    verify(inventory, never()).reserve(anyString(), anyList());
  }

  @Test
  public void reserveFailure_returnsInsufficientStock_andNothingIsPersisted() {
    when(repo.save(any(TacoOrder.class))).thenAnswer(idAssigningSave());
    when(inventory.reserve(anyString(), anyList()))
        .thenReturn(Mono.error(new tacos.inventory.InsufficientStockException("GRBF", 1)));
    OrderCreateRequest request = request(null, validTaco());

    StepVerifier.create(service.createOrder(request, CallerIdentity.anonymous()))
        .expectErrorSatisfies(error ->
            assertTrue(error instanceof tacos.inventory.InsufficientStockException,
                "expected insufficient stock"))
        .verify();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  // ------------------------------------------------------------------
  // fixtures
  // ------------------------------------------------------------------

  private static TacoValidator realValidator() {
    TacoPhysicsProperties properties = new TacoPhysicsProperties();
    return new TacoValidator(List.of(
        new SingleBaseRule(),
        new IngredientCountRule(properties),
        new NoDuplicateIngredientRule(),
        new AvailableIngredientsRule(),
        new GhostPepperNeedsDrinkRule(properties),
        new VeganNoMeatRule(properties)));
  }

  private static OrderCreateRequest request(String couponCode, TacoLineRequest taco) {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Alice");
    request.setDeliveryStreet("1 Oak");
    request.setDeliveryCity("Austin");
    request.setDeliveryState("TX");
    request.setDeliveryZip("78701");
    request.setCouponCode(couponCode);
    request.setTacos(List.of(taco));
    return request;
  }

  private static TacoLineRequest couponTaco(String ingredientId) {
    TacoLineRequest line = new TacoLineRequest();
    line.setName("Line");
    line.setIngredientIds(List.of(ingredientId));
    line.setQuantity(1);
    return line;
  }

  private static TacoLineRequest validTaco() {
    TacoLineRequest line = new TacoLineRequest();
    line.setName("Carnivore");
    line.setIngredientIds(List.of("FLTO", "GRBF"));
    line.setQuantity(1);
    return line;
  }

  private Answer<Mono<TacoOrder>> idAssigningSave() {
    return inv -> {
      TacoOrder input = inv.getArgument(0);
      TacoOrder saved = new TacoOrder();
      saved.setId("generated-" + System.nanoTime());
      saved.setPlacedAt(input.getPlacedAt());
      saved.setDeliveryName(input.getDeliveryName());
      saved.setDeliveryStreet(input.getDeliveryStreet());
      saved.setDeliveryCity(input.getDeliveryCity());
      saved.setDeliveryState(input.getDeliveryState());
      saved.setDeliveryZip(input.getDeliveryZip());
      saved.setReservationKey(input.getReservationKey());
      saved.setTacos(input.getTacos());
      saved.setCurrency(input.getCurrency());
      saved.setSubtotal(input.getSubtotal());
      saved.setDiscount(input.getDiscount());
      saved.setCouponCode(input.getCouponCode());
      saved.setTotal(input.getTotal());
      return Mono.just(saved);
    };
  }

  private static final java.math.BigDecimal ZERO = java.math.BigDecimal.ZERO;

}