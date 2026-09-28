package tacos.reorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.OrderQuoteResponse;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.ReorderRequest;
import tacos.api.dto.ReorderResponse;
import tacos.api.dto.TacoLineRequest;
import tacos.data.OrderRepository;
import tacos.web.api.AuthenticationRequiredException;import tacos.web.api.CallerIdentity;
import tacos.coupon.CouponDecision;
import tacos.coupon.CouponNotApplicableException;
import tacos.coupon.CouponStatus;
import tacos.inventory.InsufficientStockException;
import tacos.web.api.OrderAccessDeniedException;
import tacos.web.api.OrderApiService;
import tacos.web.api.OrderNotFoundException;
import tacos.web.api.UnknownIngredientException;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventMapper;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxService;

/**
 * TC-24: order it again, priced today.
 *
 * <p>{@link OrderApiService} is mocked because what is worth pinning here is
 * what the reorder service hands to it: the ingredient ids of the historical
 * lines, a payment method the customer chose, and nothing else. Pricing,
 * validation and stock belong to the shared path, and re-implementing any of
 * them here is exactly the bug this suite exists to prevent.
 */
public class ReorderServiceTest {

  private static final String ORDER_ID = "o-1";
  private static final String ALICE = "userA";

  private final CallerIdentity alice = CallerIdentity.user(ALICE);
  private final CallerIdentity operator = CallerIdentity.admin("op-1");

  private OrderRepository orders;
  private OrderApiService orderApi;
  private OutboxService outbox;
  private ReorderService reorders;

  @BeforeEach
  public void setup() {
    orders = mock(OrderRepository.class);
    orderApi = mock(OrderApiService.class);
    outbox = mock(OutboxService.class);
    when(outbox.append(any(OrderEvent.class)))
        .thenAnswer(inv -> Mono.just(new OutboxEvent()));
    reorders = new ReorderService(orders, orderApi, outbox, new OrderEventMapper());
    when(orders.findById(any(String.class))).thenReturn(Mono.empty());
    when(orderApi.quoteOrder(any(OrderCreateRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.empty());
    when(orderApi.createOrder(any(OrderCreateRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.empty());
  }

  @Test
  public void tc24_theSamePriceIsPlacedWithoutAskingAnything() {
    given(placedOrder(ALICE, line("CARNITAS", 2, "3.00", "6.00")));
    givenQuote(quoteOf("6.00", line("CARNITAS", 2, "3.00", "6.00")));
    givenCreated("new-1", "6.00");

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", false), alice))
        .assertNext(response -> {
          assertEquals(ReorderResponse.Status.CONFIRMED, response.getStatus());
          assertEquals(ORDER_ID, response.getSourceOrderId());
          assertEquals("new-1", response.getOrder().getId());
          assertTrue(response.getDifferences().isEmpty(),
              "nothing moved, so there is nothing to explain");
        })
        .verifyComplete();
  }

  @Test
  public void tc24_aDifferentPriceIsQuotedAndNothingIsCreated() {
    given(placedOrder(ALICE, line("CARNITAS", 2, "3.00", "6.00")));
    givenQuote(quoteOf("7.00", line("CARNITAS", 2, "3.50", "7.00")));

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", false), alice))
        .assertNext(response -> {
          assertEquals(ReorderResponse.Status.QUOTE, response.getStatus());
          assertNull(response.getOrder(), "a quote is not an order");
          assertMoney("6.00", response.getPreviousTotal());
          assertMoney("7.00", response.getCurrentTotal());
        })
        .verifyComplete();

    verify(orderApi, never()).createOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_theQuoteExplainsWhichLineMoved() {
    given(placedOrder(ALICE,
        line("CARNITAS", 2, "3.00", "6.00"),
        line("ALPASTE", 1, "2.00", "2.00")));
    givenQuote(quoteOf("7.00", line("CARNITAS", 2, "3.50", "7.00")));

    ReorderResponse response = reorders
        .reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertEquals(1, response.getDifferences().size());
    ReorderResponse.Difference moved = response.getDifferences().get(0);
    assertEquals("CARNITAS", moved.getTacoName());
    assertEquals(2, moved.getQuantity());
    assertMoney("3.00", moved.getPreviousUnitPrice());
    assertMoney("3.50", moved.getCurrentUnitPrice());
    assertMoney("6.00", moved.getPreviousSubtotal());
    assertMoney("7.00", moved.getCurrentSubtotal());
  }

  @Test
  public void tc24_theTotalMovingWithoutAnyLineMovingStillAsks() {
    // A discount or a delivery fee can move the total on its own; the customer
    // is asked about the number they would pay, not about the lines.
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("4.00", line("CARNITAS", 1, "3.00", "3.00")));

    ReorderResponse response = reorders
        .reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertEquals(ReorderResponse.Status.QUOTE, response.getStatus());
    assertTrue(response.getDifferences().isEmpty());
  }

  @Test
  public void tc24_linesAreComparedByPositionNotByName() {
    // The same design twice in one order: a name-based join would compare line
    // 0 against both of them and invent a difference.
    given(placedOrder(ALICE,
        line("CARNITAS", 1, "3.00", "3.00"),
        line("CARNITAS", 1, "4.00", "4.00")));
    givenQuote(quoteOf("7.00",
        line("CARNITAS", 1, "3.00", "3.00"),
        line("CARNITAS", 1, "4.00", "4.00")));
    givenCreated("new-1", "7.00");

    ReorderResponse response = reorders
        .reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertTrue(response.getDifferences().isEmpty());
  }

  @Test
  public void tc24_theCustomerCanAcceptTheNewPrice() {
    given(placedOrder(ALICE, line("CARNITAS", 2, "3.00", "6.00")));
    givenQuote(quoteOf("7.00", line("CARNITAS", 2, "3.50", "7.00")));
    givenCreated("new-1", "7.00");

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", true), alice))
        .assertNext(response -> assertEquals(ReorderResponse.Status.CONFIRMED,
            response.getStatus()))
        .verifyComplete();

    verify(orderApi, times(1)).createOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_theReorderReusesTheNormalCreatePath() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    // Quoted first, then created: the confirmation is about the price that was
    // quoted, not about a second calculation.
    verify(orderApi, times(1)).quoteOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
    verify(orderApi, times(1)).createOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_theIngredientsAreReResolvedById() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    TacoLineRequest replayed = capturedRequest().getTacos().get(0);
    assertEquals(List.of("INGR_1", "INGR_2"), replayed.getIngredientIds(),
        "a reorder carries ids so the catalog decides price and availability now");
    assertEquals("CARNITAS", replayed.getName());
    assertEquals(1, replayed.getQuantity());
  }

  @Test
  public void tc24_aLineWithNoIngredientsIsReplayedAsItWas() {
    Taco line = line("CARNITAS", 1, "3.00", "3.00");
    line.setIngredients(null);
    given(placedOrder(ALICE, line));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertTrue(capturedRequest().getTacos().get(0).getIngredientIds().isEmpty());
  }

  @Test
  public void tc24_aQuantityOfZeroDoesNotBecomeAFreeTaco() {
    given(placedOrder(ALICE, line("CARNITAS", 0, "3.00", "0.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", true), alice).block();

    assertEquals(1, capturedRequest().getTacos().get(0).getQuantity());
  }

  @Test
  public void tc24_theAddressComesFromTheOrderNotFromTheClient() {
    TacoOrder source = placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00"));
    source.setDeliveryName("Alice");
    source.setDeliveryStreet("1 Oak");
    source.setDeliveryCity("Austin");
    source.setDeliveryState("TX");
    source.setDeliveryZip("78701");
    given(source);
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    OrderCreateRequest replay = capturedRequest();
    assertEquals("Alice", replay.getDeliveryName());
    assertEquals("1 Oak", replay.getDeliveryStreet());
    assertEquals("Austin", replay.getDeliveryCity());
    assertEquals("TX", replay.getDeliveryState());
    assertEquals("78701", replay.getDeliveryZip());
  }

  @Test
  public void tc24_theOriginalPaymentReferenceIsNeverReused() {
    TacoOrder source = placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00"));
    source.setPaymentMethodId("tok_old_123");
    given(source);
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-9", false), alice).block();

    assertEquals("pay-9", capturedRequest().getPaymentMethodId());
  }

  @Test
  public void tc24_thePaymentMethodIsTrimmed() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("  pay-1  ", false), alice).block();

    assertEquals("pay-1", capturedRequest().getPaymentMethodId());
  }

  @Test
  public void tc24_theCouponOfTheOldOrderIsNotReplayed() {
    TacoOrder source = placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00"));
    source.setCouponCode("TACO10");
    given(source);
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertNull(capturedRequest().getCouponCode(),
        "a promotion that was valid then may be gone now");
  }

  @Test
  public void tc24_theIdempotencyKeyIsForwarded() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    ReorderRequest request = request("pay-1", false);
    request.setIdempotencyKey("retry-42");
    reorders.reorder(ORDER_ID, request, alice).block();

    assertEquals("retry-42", capturedRequest().getIdempotencyKey());
  }

  @Test
  public void tc24_theOriginalOrderIsNeverWritten() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    verify(orders, never()).save(any(TacoOrder.class));
    verify(orders, never()).deleteById(any(String.class));
  }

  @Test
  public void tc24_theNewOrderHasItsOwnIdentity() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    ReorderResponse response = reorders
        .reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertEquals(ORDER_ID, response.getSourceOrderId());
    assertNotEquals(ORDER_ID, response.getOrder().getId());
  }

  @Test
  public void tc24_theSourceOrderIsReadExactlyOnce() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    verify(orders, times(1)).findById(ORDER_ID);
  }

  @Test
  public void tc24_theCallerReachesTheSharedPathWithItsOwnIdentity() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    verify(orderApi).quoteOrder(any(OrderCreateRequest.class), eq(alice));
    verify(orderApi).createOrder(any(OrderCreateRequest.class), eq(alice));
  }

  @Test
  public void tc24_somebodyElsesOrderIsA403() {
    given(placedOrder("userB", line("CARNITAS", 1, "3.00", "3.00")));

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", false), alice))
        .expectError(OrderAccessDeniedException.class)
        .verify();

    verify(orderApi, never()).quoteOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_anOperatorMayReorderAnybodyElseOrder() {
    given(placedOrder("userB", line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", false), operator))
        .assertNext(response -> assertEquals(ReorderResponse.Status.CONFIRMED,
            response.getStatus()))
        .verifyComplete();
  }

  @Test
  public void tc24_anAnonymousCallerIsA401() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", false),
        CallerIdentity.anonymous()))
        .expectError(AuthenticationRequiredException.class)
        .verify();

    verify(orderApi, never()).quoteOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_anOrderThatDoesNotExistIsA404() {
    StepVerifier.create(reorders.reorder("ghost", request("pay-1", false), alice))
        .expectError(OrderNotFoundException.class)
        .verify();

    verify(orderApi, never()).quoteOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_aBlankPaymentMethodIsRefused() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));

    StepVerifier.create(reorders.reorder(ORDER_ID, request("   ", false), alice))
        .expectError(ReorderPaymentMethodRequiredException.class)
        .verify();

    verify(orders, never()).findById(any(String.class));
  }

  @Test
  public void tc24_thePaymentMethodIsRequiredBeforeTheOrderIsEvenRead() {
    StepVerifier.create(reorders.reorder(ORDER_ID, request(null, false), alice))
        .expectError(ReorderPaymentMethodRequiredException.class)
        .verify();

    verify(orders, never()).findById(any(String.class));
    verify(orderApi, never()).quoteOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_aRetiredIngredientIsRefusedByTheSharedPath() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    when(orderApi.quoteOrder(any(OrderCreateRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.error(new UnknownIngredientException("INGR_9")));

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", true), alice))
        .expectError(UnknownIngredientException.class)
        .verify();

    verify(orderApi, never()).createOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_anIngredientOutOfStockIsRefusedByTheSharedPath() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    when(orderApi.quoteOrder(any(OrderCreateRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.error(new InsufficientStockException("INGR_1", 3)));

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", true), alice))
        .expectError(InsufficientStockException.class)
        .verify();

    verify(orderApi, never()).createOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_aFailedQuoteCreatesNothing() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    when(orderApi.quoteOrder(any(OrderCreateRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.error(new CouponNotApplicableException(
            CouponDecision.rejected(CouponStatus.EXPIRED, "TACO10", "not valid"))));

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", true), alice))
        .expectError(CouponNotApplicableException.class)
        .verify();

    verify(orderApi, never()).createOrder(any(OrderCreateRequest.class),
        any(CallerIdentity.class));
  }

  @Test
  public void tc24_anOrderWithNoRecordedTotalNeedsNoConfirmation() {
    TacoOrder source = placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00"));
    source.setTotal(null);
    given(source);
    givenQuote(quoteOf("9.99", line("CARNITAS", 1, "9.99", "9.99")));
    givenCreated("new-1", "9.99");

    StepVerifier.create(reorders.reorder(ORDER_ID, request("pay-1", false), alice))
        .assertNext(response -> assertEquals(ReorderResponse.Status.CONFIRMED,
            response.getStatus()))
        .verifyComplete();
  }

  @Test
  public void tc24_anOrderWithNoTacosIsReplayedAsAnEmptyOrder() {
    TacoOrder source = new TacoOrder();
    source.setId(ORDER_ID);
    source.setUser(user(ALICE));
    source.setPlacedAt(new Date());
    source.setCurrency("USD");
    source.setTotal(new BigDecimal("0.00"));
    given(source);
    givenQuote(quoteOf("0.00"));
    givenCreated("new-1", "0.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertTrue(capturedRequest().getTacos().isEmpty());
  }

  @Test
  public void tc24_everyLineOfTheOrderIsReplayed() {
    given(placedOrder(ALICE,
        line("CARNITAS", 2, "3.00", "6.00"),
        line("ALPASTE", 1, "2.00", "2.00"),
        line("Cebolla", 1, "1.00", "1.00")));
    givenQuote(quoteOf("9.00",
        line("CARNITAS", 2, "3.00", "6.00"),
        line("ALPASTE", 1, "2.00", "2.00"),
        line("Cebolla", 1, "1.00", "1.00")));
    givenCreated("new-1", "9.00");

    reorders.reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertEquals(3, capturedRequest().getTacos().size());
  }

  @Test
  public void tc24_theQuoteCarriesNoOrderField() throws Exception {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.50", line("CARNITAS", 1, "3.50", "3.50")));

    ReorderResponse response = reorders
        .reorder(ORDER_ID, request("pay-1", false), alice).block();

    String json = new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .writeValueAsString(response);
    assertFalse(json.contains("\"order\""), json);
    assertTrue(json.contains("\"status\":\"QUOTE\""), json);
  }

  @Test
  public void tc24_thePlacedAnswerCarriesBothTheQuoteAndTheNewOrder() {
    given(placedOrder(ALICE, line("CARNITAS", 1, "3.00", "3.00")));
    givenQuote(quoteOf("3.00", line("CARNITAS", 1, "3.00", "3.00")));
    givenCreated("new-1", "3.00");

    ReorderResponse response = reorders
        .reorder(ORDER_ID, request("pay-1", false), alice).block();

    assertMoney("3.00", response.getQuote().getTotal());
    assertMoney("3.00", response.getOrder().getTotal());
    assertNotNull(response.getOrder());
  }

  private static void assertNotNull(Object value) {
    org.junit.jupiter.api.Assertions.assertNotNull(value);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " but was " + actual);
  }

  private OrderCreateRequest capturedRequest() {
    ArgumentCaptor<OrderCreateRequest> captured =
        ArgumentCaptor.forClass(OrderCreateRequest.class);
    verify(orderApi).createOrder(captured.capture(), any(CallerIdentity.class));
    return captured.getValue();
  }

  private void given(TacoOrder source) {
    when(orders.findById(ORDER_ID)).thenReturn(Mono.just(source));
  }

  private void givenQuote(OrderQuoteResponse quote) {
    when(orderApi.quoteOrder(any(OrderCreateRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.just(quote));
  }

  private void givenCreated(String id, String total) {
    TacoOrder created = new TacoOrder();
    created.setId(id);
    created.setPlacedAt(new Date());
    created.setCurrency("USD");
    created.setTotal(new BigDecimal(total));
    when(orderApi.createOrder(any(OrderCreateRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.just(created));
  }

  private static ReorderRequest request(String paymentMethodId, boolean confirm) {
    ReorderRequest request = new ReorderRequest();
    request.setPaymentMethodId(paymentMethodId);
    request.setConfirmPriceChange(confirm);
    return request;
  }

  /** A quote of an order priced today, built through the same factory as the API. */
  private static OrderQuoteResponse quoteOf(String total, Taco... lines) {
    TacoOrder priced = new TacoOrder();
    priced.setCurrency("USD");
    priced.setTacos(new ArrayList<>(Arrays.asList(lines)));
    priced.setSubtotal(new BigDecimal(total));
    priced.setTotal(new BigDecimal(total));
    return OrderQuoteResponse.of(priced);
  }

  private static TacoOrder placedOrder(String userId, Taco... lines) {
    TacoOrder order = new TacoOrder();
    order.setId(ORDER_ID);
    order.setUser(user(userId));
    order.setPlacedAt(new Date());
    order.setTacos(new ArrayList<>(Arrays.asList(lines)));
    order.setCurrency("USD");
    BigDecimal total = BigDecimal.ZERO;
    for (Taco line : lines) {
      total = total.add(line.getSubtotal() == null ? BigDecimal.ZERO : line.getSubtotal());
    }
    order.setTotal(total);
    return order;
  }

  private static Taco line(String name, int quantity, String unitPrice, String subtotal) {
    Taco line = new Taco();
    line.setName(name);
    line.setQuantity(quantity);
    line.setUnitPriceAtPurchase(new BigDecimal(unitPrice));
    line.setSubtotal(new BigDecimal(subtotal));
    line.setIngredients(new ArrayList<>(
        Arrays.asList(ingredient("INGR_1"), ingredient("INGR_2"))));
    return line;
  }

  private static Ingredient ingredient(String id) {
    Ingredient ingredient = new Ingredient(id, "Ingredient " + id, Ingredient.Type.PROTEIN);
    ingredient.setAvailable(true);
    return ingredient;
  }

  private static User user(String id) {
    User user = new User("alice", "pw", "Alice", "1 Oak", "Austin", "TX", "78701", "555",
        id + "@taco.cl");
    user.setId(id);
    user.setRole("ROLE_USER");
    return user;
  }

}
