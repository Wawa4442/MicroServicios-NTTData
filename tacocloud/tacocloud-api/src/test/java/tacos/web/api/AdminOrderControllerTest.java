package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;

/**
 * TC-23's operator view.
 *
 * <p>{@code /api/admin/orders} talks to the repository directly, so the cases
 * below are mostly about which query each variant picks: every order, or one
 * customer's. The authorization rule itself is not tested here — it lives in the
 * security configuration, where a mocked controller would not prove anything.
 */
public class AdminOrderControllerTest {

  private OrderRepository orders;
  private WebTestClient client;

  @BeforeEach
  public void setup() {
    orders = mock(OrderRepository.class);
    when(orders.count()).thenReturn(Mono.just(0L));
    when(orders.countByUser_Id(anyString())).thenReturn(Mono.just(0L));
    when(orders.findAllNewestFirst(any(Pageable.class))).thenReturn(Flux.empty());
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(anyString(), any(Pageable.class)))
        .thenReturn(Flux.empty());
    client = WebTestClient.bindToController(new AdminOrderController(orders))
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  @Test
  public void tc23_theOperatorSeesEveryOrderNewestFirst() {
    when(orders.count()).thenReturn(Mono.just(42L));
    when(orders.findAllNewestFirst(any(Pageable.class)))
        .thenReturn(Flux.just(order("o-1"), order("o-2")));

    client.get().uri("/api/admin/orders")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].id").isEqualTo("o-1")
        .jsonPath("$.totalElements").isEqualTo(42)
        .jsonPath("$.size").isEqualTo(20);

    verify(orders).findAllNewestFirst(any(Pageable.class));
    verify(orders, never()).countByUser_Id(anyString());
  }

  @Test
  public void tc23_theOperatorWindowComesFromTheQueryString() {
    client.get().uri("/api/admin/orders?page=1&size=5")
        .exchange()
        .expectStatus().isOk();

    ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
    verify(orders).findAllNewestFirst(window.capture());
    assertEquals(1, window.getValue().getPageNumber());
    assertEquals(5, window.getValue().getPageSize());
  }

  @Test
  public void tc23_theOperatorCanNarrowToOneCustomer() {
    when(orders.countByUser_Id("userB")).thenReturn(Mono.just(2L));
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(eq("userB"), any(Pageable.class)))
        .thenReturn(Flux.just(order("o-9")));

    client.get().uri("/api/admin/orders?userId=userB")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].id").isEqualTo("o-9")
        .jsonPath("$.totalElements").isEqualTo(2);

    verify(orders, never()).count();
    verify(orders, never()).findAllNewestFirst(any(Pageable.class));
  }

  @Test
  public void tc23_aBlankFilterIsNotAFilter() {
    when(orders.count()).thenReturn(Mono.just(1L));

    client.get().uri(builder -> builder.path("/api/admin/orders")
        .queryParam("userId", " ")
        .build())
        .exchange()
        .expectStatus().isOk();

    verify(orders).count();
    verify(orders).findAllNewestFirst(any(Pageable.class));
    verify(orders, never()).countByUser_Id(anyString());
  }

  @Test
  public void tc23_theOperatorFilterIsTrimmed() {
    client.get().uri(builder -> builder.path("/api/admin/orders")
        .queryParam("userId", " userB ")
        .build())
        .exchange()
        .expectStatus().isOk();

    verify(orders).countByUser_Id("userB");
  }

  @Test
  public void tc23_theOperatorWindowHasItsOwnCeiling() {
    client.get().uri("/api/admin/orders?size=500")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_page");

    verify(orders, never()).findAllNewestFirst(any(Pageable.class));
  }

  @Test
  public void tc23_theOperatorListingIsNotAReceipt() {
    when(orders.findAllNewestFirst(any(Pageable.class))).thenReturn(Flux.just(order("o-1")));

    client.get().uri("/api/admin/orders")
        .exchange()
        .expectStatus().isOk()
        .expectBody(String.class)
        .value(body -> {
          assertFalse(body.contains("street"), body);
          assertFalse(body.contains("cvv"), body);
          assertFalse(body.contains("password"), body);
        });
  }

  @Test
  public void tc23_anEmptyShopIsAnEmptyPage() {
    client.get().uri("/api/admin/orders")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content").isArray()
        .jsonPath("$.totalElements").isEqualTo(0)
        .jsonPath("$.hasNext").isEqualTo(false);
  }

  @Test
  public void tc23_theOperatorListingIsReadOnce() {
    client.get().uri("/api/admin/orders")
        .exchange()
        .expectStatus().isOk();

    verify(orders, times(1)).findAllNewestFirst(any(Pageable.class));
    verify(orders, times(1)).count();
  }

  @Test
  public void tc23_theOperatorCannotReachTheDetailOfAnOrderFromThisRoute() {
    client.get().uri("/api/admin/orders/o-1")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  public void tc23_theOperatorLineIdentifiesTheOrderByItsTacos() {
    when(orders.findAllNewestFirst(any(Pageable.class))).thenReturn(Flux.just(order("o-1")));

    client.get().uri("/api/admin/orders")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].id").isEqualTo("o-1")
        .jsonPath("$.content[0].tacoNames[0]").isEqualTo("CARNITAS")
        .jsonPath("$.content[0].tacoCount").isEqualTo(1);
  }

  private static TacoOrder order(String id) {
    Taco taco = new Taco();
    taco.setName("CARNITAS");
    User user = new User("alice", "pw", "Alice", "1 Oak", "Austin", "TX", "78701", "555",
        "alice@taco.cl");
    user.setId("userA");
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setPlacedAt(new Date());
    order.setTacos(List.of(taco));
    order.setTotal(new BigDecimal("8.50"));
    order.setCurrency("USD");
    order.setUser(user);
    return order;
  }

}
