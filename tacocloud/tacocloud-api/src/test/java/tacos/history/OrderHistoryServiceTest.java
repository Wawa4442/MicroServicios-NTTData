package tacos.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderSummaryResponse;
import tacos.api.dto.PageResponse;
import tacos.data.OrderRepository;
import tacos.web.api.OrderNotFoundException;

/**
 * TC-23: a customer's own order history, and nobody else's.
 *
 * <p>The repository is mocked and the queries are inspected. That is the point
 * of these cases: an implementation that loads every order and filters in Java
 * would pass a response-shape test and leak the other customers' order counts
 * through the pager, and the only way to see the difference is to look at the
 * query.
 */
public class OrderHistoryServiceTest {

  private static final String ALICE = "userA";
  private static final String BOB = "userB";

  private OrderRepository orders;
  private OrderHistoryService history;

  @BeforeEach
  public void setup() {
    orders = mock(OrderRepository.class);
    history = new OrderHistoryService(orders);
    when(orders.countByUser_Id(any())).thenReturn(Mono.just(0L));
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(any(), any(Pageable.class)))
        .thenReturn(Flux.empty());
    when(orders.findById(any(String.class))).thenReturn(Mono.empty());
  }

  @Test
  public void tc23_theHistoryIsScopedToTheOwnerInTheQuery() {
    when(orders.countByUser_Id(ALICE)).thenReturn(Mono.just(3L));
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE), any(Pageable.class)))
        .thenReturn(Flux.just(order("o-1", ALICE), order("o-2", ALICE), order("o-3", ALICE)));

    StepVerifier.create(history.history(ALICE, 0, 10))
        .assertNext(page -> {
          assertEquals(3, page.getContent().size());
          assertEquals(3L, page.getTotalElements());
        })
        .verifyComplete();

    verify(orders).countByUser_Id(ALICE);
    verify(orders, never()).count();
    verify(orders, never()).findAllNewestFirst(any(Pageable.class));
  }

  @Test
  public void tc23_theSummaryDoesNotCarryTheCustomerOrTheAddress() {
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE), any(Pageable.class)))
        .thenReturn(Flux.just(order("o-1", ALICE)));

    OrderSummaryResponse summary = history.history(ALICE, 0, 10).block().getContent().get(0);

    List<String> fields = declaredFieldNames(OrderSummaryResponse.class);
    assertTrue(fields.contains("total") && fields.contains("tacoNames"));
    assertTrue(fields.stream().noneMatch(name -> name.contains("user")
            || name.contains("address") || name.contains("payment")
            || name.contains("card") || name.contains("pan")),
        "a history line is not a receipt: " + fields);
  }

  @Test
  public void tc23_theSummaryCarriesWhatACustomerLooksFor() {
    TacoOrder placed = order("o-1", ALICE);
    placed.setTacos(List.of(taco("CARNITAS"), taco("ALPASTE")));
    placed.setTotal(new BigDecimal("12.50"));
    placed.setCurrency("USD");
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE), any(Pageable.class)))
        .thenReturn(Flux.just(placed));

    OrderSummaryResponse summary = history.history(ALICE, 0, 10).block().getContent().get(0);

    assertEquals("o-1", summary.getId());
    assertEquals(List.of("CARNITAS", "ALPASTE"), summary.getTacoNames());
    assertEquals(2, summary.getTacoCount());
    assertEquals(new BigDecimal("12.50"), summary.getTotal());
    assertEquals("USD", summary.getCurrency());
  }

  @Test
  public void tc23_theWindowIsTakenFromTheRequest() {
    history.history(ALICE, 2, 5).block();

    ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
    verify(orders).findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE), window.capture());
    assertEquals(2, window.getValue().getPageNumber());
    assertEquals(5, window.getValue().getPageSize());
  }

  @Test
  public void tc23_theTotalIsTheCustomersTotalNotTheShops() {
    when(orders.countByUser_Id(ALICE)).thenReturn(Mono.just(7L));
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE), any(Pageable.class)))
        .thenReturn(Flux.just(order("o-1", ALICE)));

    PageResponse<OrderSummaryResponse> page = history.history(ALICE, 0, 10).block();

    assertEquals(7L, page.getTotalElements());
    assertEquals(1, page.getTotalPages());
    assertTrue(page.isHasNext() == false);
    verify(orders, never()).count();
  }

  @Test
  public void tc23_thePagerKnowsWhetherThereIsAnotherPage() {
    when(orders.countByUser_Id(ALICE)).thenReturn(Mono.just(25L));

    assertTrue(history.history(ALICE, 0, 10).block().isHasNext());
    assertTrue(history.history(ALICE, 1, 10).block().isHasNext());
    assertTrue(history.history(ALICE, 2, 10).block().isHasNext() == false);
  }

  @Test
  public void tc23_anEmptyHistoryIsAnEmptyPage() {
    StepVerifier.create(history.history(ALICE, 0, 10))
        .assertNext(page -> {
          assertTrue(page.getContent().isEmpty());
          assertEquals(0L, page.getTotalElements());
          assertEquals(0, page.getTotalPages());
          assertTrue(page.isHasNext() == false);
        })
        .verifyComplete();
  }

  @Test
  public void tc23_theOwnOrderIsReturned() {
    when(orders.findById("o-1")).thenReturn(Mono.just(order("o-1", ALICE)));

    StepVerifier.create(history.detail(ALICE, "o-1"))
        .assertNext(order -> assertEquals("o-1", order.getId()))
        .verifyComplete();
  }

  @Test
  public void tc23_somebodyElsesOrderIsA404NotA403() {
    when(orders.findById("o-9")).thenReturn(Mono.just(order("o-9", BOB)));

    // A 403 would confirm the order exists, which turns this route into an
    // oracle for guessing which order ids are real.
    StepVerifier.create(history.detail(ALICE, "o-9"))
        .expectError(OrderNotFoundException.class)
        .verify();
  }

  @Test
  public void tc23_anOrderThatDoesNotExistIsTheSame404() {
    when(orders.findById("ghost")).thenReturn(Mono.empty());

    StepVerifier.create(history.detail(ALICE, "ghost"))
        .expectError(OrderNotFoundException.class)
        .verify();
  }

  @Test
  public void tc23_anOrderWithoutAnOwnerIsNobodyElsesOrder() {
    TacoOrder orphan = order("o-8", ALICE);
    orphan.setUser(null);
    when(orders.findById("o-8")).thenReturn(Mono.just(orphan));

    StepVerifier.create(history.detail(ALICE, "o-8"))
        .expectError(OrderNotFoundException.class)
        .verify();
  }

  @Test
  public void tc23_theOwnerIsComparedOnTheIdNotOnTheEmail() {
    User owner = user("userA", "alice@taco.cl");
    TacoOrder placed = new TacoOrder();
    placed.setId("o-1");
    placed.setUser(owner);
    when(orders.findById("o-1")).thenReturn(Mono.just(placed));

    StepVerifier.create(history.detail(ALICE, "o-1"))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  public void tc23_aBlankOwnerIsNotAMatchEither() {
    TacoOrder placed = new TacoOrder();
    placed.setId("o-1");
    User owner = user("userA", "alice@taco.cl");
    owner.setId(null);
    placed.setUser(owner);
    when(orders.findById("o-1")).thenReturn(Mono.just(placed));

    StepVerifier.create(history.detail(ALICE, "o-1"))
        .expectError(OrderNotFoundException.class)
        .verify();
  }

  @Test
  public void tc23_theHistoryIsReadOnceNotOncePerOrder() {
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE), any(Pageable.class)))
        .thenReturn(Flux.just(order("o-1", ALICE), order("o-2", ALICE)));

    history.history(ALICE, 0, 10).block();

    verify(orders, times(1)).findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE),
        any(Pageable.class));
    verify(orders, times(1)).countByUser_Id(ALICE);
    verify(orders, never()).findById(any(String.class));
  }

  @Test
  public void tc23_aSlowCountDoesNotLoseTheRows() {
    when(orders.countByUser_Id(ALICE)).thenReturn(
        Mono.just(2L).delayElement(java.time.Duration.ofMillis(20)));
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE), any(Pageable.class)))
        .thenReturn(Flux.just(order("o-1", ALICE), order("o-2", ALICE)));

    StepVerifier.create(history.history(ALICE, 0, 10))
        .assertNext(page -> {
          assertEquals(2, page.getContent().size());
          assertEquals(2L, page.getTotalElements());
        })
        .verifyComplete();
  }

  @Test
  public void tc23_anOrderWithNoTacosIsStillALine() {
    TacoOrder bare = order("o-1", ALICE);
    bare.setTacos(null);
    when(orders.findByUser_IdOrderByPlacedAtDescIdDesc(eq(ALICE), any(Pageable.class)))
        .thenReturn(Flux.just(bare));

    OrderSummaryResponse summary = history.history(ALICE, 0, 10).block().getContent().get(0);

    assertTrue(summary.getTacoNames().isEmpty());
    assertEquals(0, summary.getTacoCount());
  }

  private static List<String> declaredFieldNames(Class<?> type) {
    List<String> names = new java.util.ArrayList<>();
    java.util.Arrays.stream(type.getDeclaredFields())
        .forEach(field -> names.add(field.getName()));
    return names;
  }

  private static User user(String id, String email) {
    User user = new User("alice", "pw", "Alice", "1 Oak", "Austin", "TX", "78701", "555",
        email);
    user.setId(id);
    user.setRole("ROLE_USER");
    return user;
  }

  private static Taco taco(String name) {
    Taco taco = new Taco();
    taco.setName(name);
    return taco;
  }

  private static TacoOrder order(String id, String userId) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUser(user(userId, userId + "@taco.cl"));
    order.setPlacedAt(new Date());
    order.setTacos(List.of(taco("CARNITAS")));
    return order;
  }

}
