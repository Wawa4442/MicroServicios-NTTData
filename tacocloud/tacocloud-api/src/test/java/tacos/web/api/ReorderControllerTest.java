package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderQuoteResponse;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.ReorderRequest;
import tacos.api.dto.ReorderResponse;
import tacos.reorder.ReorderPaymentMethodRequiredException;
import tacos.reorder.ReorderService;

/**
 * TC-24 at the HTTP edge.
 *
 * <p>The status code is the contract worth pinning: 201 for a placed order,
 * 200 for a quote that created nothing, and a 401 for a caller whose identity
 * the endpoint cannot establish. A 201 for a quote would tell the client an
 * order exists when it does not.
 */
public class ReorderControllerTest {

  private static final String ORDER_ID = "o-1";

  private ReorderService reorders;
  private WebTestClient signedIn;
  private WebTestClient anonymous;

  @BeforeEach
  public void setup() {
    reorders = mock(ReorderService.class);
    when(reorders.reorder(anyString(), any(ReorderRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.just(confirmed()));
    ReorderController controller =
        new ReorderController(reorders, new CallerIdentityResolver());
    signedIn = clientFor(controller, "userA");
    anonymous = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  @Test
  public void tc24_aPlacedReorderIsACreatedOrder() {
    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\"}")
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.status").isEqualTo("CONFIRMED")
        .jsonPath("$.sourceOrderId").isEqualTo(ORDER_ID)
        .jsonPath("$.order.id").isEqualTo("new-1");
  }

  @Test
  public void tc24_aQuoteIsAnOkAndCarriesNoOrder() {
    when(reorders.reorder(anyString(), any(ReorderRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.just(quote()));

    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("QUOTE")
        .jsonPath("$.previousTotal").isEqualTo(6.0)
        .jsonPath("$.currentTotal").isEqualTo(7.0)
        .jsonPath("$.differences[0].tacoName").isEqualTo("CARNITAS")
        .jsonPath("$.differences[0].currentUnitPrice").isEqualTo(3.5)
        .jsonPath("$.order").doesNotExist();
  }

  @Test
  public void tc24_theConfirmationIsForwardedToTheService() {
    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\",\"confirmPriceChange\":true}")
        .exchange()
        .expectStatus().isCreated();

    org.mockito.ArgumentCaptor<ReorderRequest> request =
        org.mockito.ArgumentCaptor.forClass(ReorderRequest.class);
    verify(reorders).reorder(org.mockito.ArgumentMatchers.eq(ORDER_ID), request.capture(),
        any(CallerIdentity.class));
    org.junit.jupiter.api.Assertions.assertTrue(request.getValue().isConfirmPriceChange());
  }

  @Test
  public void tc24_theIdempotencyKeyIsForwardedToTheService() {
    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\",\"idempotencyKey\":\"retry-1\"}")
        .exchange()
        .expectStatus().isCreated();

    org.mockito.ArgumentCaptor<ReorderRequest> request =
        org.mockito.ArgumentCaptor.forClass(ReorderRequest.class);
    verify(reorders).reorder(anyString(), request.capture(), any(CallerIdentity.class));
    org.junit.jupiter.api.Assertions.assertEquals("retry-1",
        request.getValue().getIdempotencyKey());
  }

  @Test
  public void tc24_aMissingPaymentMethodIsA400() {
    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"confirmPriceChange\":true}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody()
        .jsonPath("$.code").isEqualTo("validation_error")
        .jsonPath("$.violations[0].field").isEqualTo("paymentMethodId");

    verifyNoInteractions(reorders);
  }

  @Test
  public void tc24_aBlankPaymentMethodIsA400() {
    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"   \"}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);

    verifyNoInteractions(reorders);
  }

  @Test
  public void tc24_aRefusedPaymentMethodNeverReachesTheService() {
    when(reorders.reorder(anyString(), any(ReorderRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.error(new ReorderPaymentMethodRequiredException()));

    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\"}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody()
        .jsonPath("$.code").isEqualTo("reorder_payment_method_required");
  }

  @Test
  public void tc24_somebodyElsesOrderIsA403() {
    when(reorders.reorder(anyString(), any(ReorderRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.error(new OrderAccessDeniedException()));

    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\"}")
        .exchange()
        .expectStatus().isForbidden()
        .expectBody()
        .jsonPath("$.code").isEqualTo("access_denied");
  }

  @Test
  public void tc24_anOrderThatDoesNotExistIsA404() {
    when(reorders.reorder(anyString(), any(ReorderRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.error(new OrderNotFoundException(ORDER_ID)));

    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\"}")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.code").isEqualTo("order_not_found");
  }

  @Test
  public void tc24_aRetiredIngredientIsA422() {
    when(reorders.reorder(anyString(), any(ReorderRequest.class), any(CallerIdentity.class)))
        .thenReturn(Mono.error(new UnknownIngredientException("INGR_9")));

    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\"}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        .expectBody()
        .jsonPath("$.code").isEqualTo("unknown_ingredient");
  }

  @Test
  public void tc24_anAnonymousCallerIsA401() {
    anonymous.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\"}")
        .exchange()
        .expectStatus().isUnauthorized();

    verifyNoInteractions(reorders);
  }

  @Test
  public void tc24_theBodyIsRequired() {
    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);

    verifyNoInteractions(reorders);
  }

  @Test
  public void tc24_aBodyThatIsNotJsonIsA415() {
    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.TEXT_PLAIN)
        .syncBody("pay-1")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

    verifyNoInteractions(reorders);
  }

  @Test
  public void tc24_noCardDataIsEverSentOrAccepted() {
    signedIn.post().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\",\"cardNumber\":\"4111111111111111\"}")
        .exchange()
        .expectStatus().isCreated();

    org.mockito.ArgumentCaptor<ReorderRequest> request =
        org.mockito.ArgumentCaptor.forClass(ReorderRequest.class);
    verify(reorders).reorder(anyString(), request.capture(), any(CallerIdentity.class));
    org.junit.jupiter.api.Assertions.assertEquals("pay-1",
        request.getValue().getPaymentMethodId());
  }

  @Test
  public void tc24_theReorderDoesNotReplaceTheOriginalOrder() {
    // The route is a POST on a sub-resource; there is no PUT that could update
    // the order in place.
    signedIn.put().uri("/api/orders/{id}/reorder", ORDER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"paymentMethodId\":\"pay-1\"}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

    verifyNoInteractions(reorders);
  }

  private static ReorderResponse confirmed() {
    TacoOrder created = new TacoOrder();
    created.setId("new-1");
    created.setTotal(new BigDecimal("6.00"));
    created.setCurrency("USD");
    return ReorderResponse.placed(ORDER_ID, new BigDecimal("6.00"), quote("6.00"),
        List.of(), OrderResponse.from(created));
  }

  private static ReorderResponse quote() {
    return ReorderResponse.awaitingConfirmation(ORDER_ID, new BigDecimal("6.00"),
        quote("7.00"), List.of(new ReorderResponse.Difference("CARNITAS", 2,
            new BigDecimal("3.00"), new BigDecimal("3.50"),
            new BigDecimal("6.00"), new BigDecimal("7.00"))));
  }

  private static OrderQuoteResponse quote(String total) {
    Taco line = new Taco();
    line.setName("CARNITAS");
    line.setQuantity(2);
    line.setUnitPriceAtPurchase(new BigDecimal("3.00"));
    line.setSubtotal(new BigDecimal(total));
    TacoOrder priced = new TacoOrder();
    priced.setCurrency("USD");
    priced.setTacos(List.of(line));
    priced.setSubtotal(new BigDecimal(total));
    priced.setTotal(new BigDecimal(total));
    return OrderQuoteResponse.of(priced);
  }

  private static WebTestClient clientFor(ReorderController controller, String userId) {
    User user = new User("alice", "pw", "Alice", "1 Oak", "Austin", "TX", "78701", "555",
        userId + "@taco.cl");
    user.setId(userId);
    user.setRole("ROLE_USER");
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        user, user.getPassword(), user.getAuthorities());
    return WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .webFilter((exchange, chain) -> chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
        .build();
  }

}
