package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderSummaryResponse;
import tacos.api.dto.PageResponse;
import tacos.data.OrderRepository;
import tacos.history.OrderHistoryProperties;
import tacos.history.OrderHistoryService;

/**
 * TC-23 at the HTTP edge: {@code /api/users/me/orders}.
 *
 * <p>The service is mocked so that the contract is under test: the window comes
 * from the query string, the caller comes from the session, and a missing
 * {@code userId} parameter is not a way to read somebody else's history.
 */
public class OrderHistoryControllerTest {

  private static final String ORDER_ID = "o-1";

  private OrderHistoryService history;
  private WebTestClient signedIn;
  private WebTestClient anonymous;

  @BeforeEach
  public void setup() {
    history = mock(OrderHistoryService.class);
    when(history.history(anyString(), anyInt(), anyInt()))
        .thenReturn(Mono.just(PageResponse.of(List.of(summary("o-1")), 0, 10, 1)));
    when(history.detail(anyString(), anyString()))
        .thenReturn(Mono.just(tacos.api.dto.OrderResponse.from(order("o-1"))));

    OrderHistoryController controller =
        new OrderHistoryController(history, new CallerIdentityResolver(),
            new OrderHistoryProperties());
    signedIn = clientFor(controller, "userA");
    anonymous = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  @Test
  public void tc23_aCustomerReadsTheirOwnHistory() {
    signedIn.get().uri("/api/users/me/orders")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].id").isEqualTo(ORDER_ID)
        .jsonPath("$.content[0].tacoCount").isEqualTo(1)
        .jsonPath("$.content[0].total").isEqualTo(8.5)
        .jsonPath("$.totalElements").isEqualTo(1)
        .jsonPath("$.hasNext").isEqualTo(false);

    verify(history).history("userA", 0, 10);
  }

  @Test
  public void tc23_theWindowComesFromTheQueryString() {
    signedIn.get().uri("/api/users/me/orders?page=2&size=5")
        .exchange()
        .expectStatus().isOk();

    verify(history).history("userA", 2, 5);
  }

  @Test
  public void tc23_theDefaultWindowIsTheConfiguredOne() {
    OrderHistoryProperties properties = new OrderHistoryProperties();
    properties.setDefaultSize(25);
    properties.setMaxSize(75);
    OrderHistoryController controller =
        new OrderHistoryController(history, new CallerIdentityResolver(), properties);

    clientFor(controller, "userA").get().uri("/api/users/me/orders")
        .exchange()
        .expectStatus().isOk();

    verify(history).history("userA", 0, 25);
  }

  @Test
  public void tc23_aWindowBiggerThanTheCeilingIsA400() {
    signedIn.get().uri("/api/users/me/orders?size=5000")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_page");

    verify(history, never()).history(anyString(), anyInt(), anyInt());
  }

  @Test
  public void tc23_aNegativePageIsA400() {
    signedIn.get().uri("/api/users/me/orders?page=-1")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);

    verify(history, never()).history(anyString(), anyInt(), anyInt());
  }

  @Test
  public void tc23_theIdentityIsNeverTakenFromAParameter() {
    signedIn.get().uri("/api/users/me/orders?userId=userB")
        .exchange()
        .expectStatus().isOk();

    verify(history).history("userA", 0, 10);
  }

  @Test
  public void tc23_theHistoryNeedsASignIn() {
    anonymous.get().uri("/api/users/me/orders")
        .exchange()
        .expectStatus().isUnauthorized();

    verifyNoHistoryCalls();
  }

  @Test
  public void tc23_theDetailIsTheCallersOwn() {
    signedIn.get().uri("/api/users/me/orders/{id}", ORDER_ID)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo(ORDER_ID);

    verify(history).detail("userA", ORDER_ID);
  }

  @Test
  public void tc23_somebodyElsesOrderLooksExactlyLikeAnOrderThatDoesNotExist() {
    when(history.detail("userA", "o-9"))
        .thenReturn(Mono.error(new OrderNotFoundException("o-9")));

    signedIn.get().uri("/api/users/me/orders/o-9")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.code").isEqualTo("order_not_found");
  }

  @Test
  public void tc23_theDetailNeedsASignIn() {
    anonymous.get().uri("/api/users/me/orders/{id}", ORDER_ID)
        .exchange()
        .expectStatus().isUnauthorized();

    verify(history, never()).detail(anyString(), anyString());
  }

  @Test
  public void tc23_theHistoryNeverCarriesTheAddressOrThePayment() {
    signedIn.get().uri("/api/users/me/orders")
        .exchange()
        .expectStatus().isOk()
        .expectBody(String.class)
        .value(body -> {
          assertFalse(body.contains("street"), body);
          assertFalse(body.contains("zip"), body);
          assertFalse(body.contains("cardNumber"), body);
          assertFalse(body.contains("userId"), body);
        });
  }

  @Test
  public void tc23_thereIsNoRouteForSomebodyElsesHistory() {
    signedIn.get().uri("/api/users/userB/orders")
        .exchange()
        .expectStatus().isNotFound();

    verify(history, never()).history(anyString(), anyInt(), anyInt());
    verify(history, never()).detail(anyString(), anyString());
  }

  @Test
  public void tc23_theLastSegmentIsAnOrderIdAndNothingElse() {
    // "all" is not a magic value: it is looked up as an order id, and a real
    // deployment answers order_not_found for it.
    signedIn.get().uri("/api/users/me/orders/all")
        .exchange()
        .expectStatus().isOk();

    verify(history).detail("userA", "all");
    verify(history, never()).history(anyString(), anyInt(), anyInt());
  }

  @Test
  public void tc23_theHistoryIsReadOncePerRequest() {
    signedIn.get().uri("/api/users/me/orders")
        .exchange()
        .expectStatus().isOk();

    verify(history, times(1)).history(anyString(), anyInt(), anyInt());
  }

  private void verifyNoHistoryCalls() {
    verify(history, never()).history(anyString(), anyInt(), anyInt());
  }

  private static WebTestClient clientFor(OrderHistoryController controller, String userId) {
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

  private static OrderSummaryResponse summary(String id) {
    return OrderSummaryResponse.from(order(id));
  }

  private static TacoOrder order(String id) {
    Taco taco = new Taco();
    taco.setName("CARNITAS");
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setPlacedAt(new Date());
    order.setTacos(List.of(taco));
    order.setTotal(new BigDecimal("8.50"));
    order.setCurrency("USD");
    return order;
  }

}
