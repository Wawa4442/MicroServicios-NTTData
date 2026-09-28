package tacos.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.web.api.AuthenticationRequiredException;
import tacos.web.api.CallerIdentity;
import tacos.web.api.OrderAccessDeniedException;
import tacos.web.api.OrderNotFoundException;

/**
 * TC-25: the workflow service on top of a mocked repository.
 */
public class OrderWorkflowServiceTest {

  private OrderRepository repo;
  private OrderWorkflowService service;

  @BeforeEach
  public void setup() {
    repo = Mockito.mock(OrderRepository.class);
    service = new OrderWorkflowService(repo);
  }

  @Test
  public void tc25_kitchenAdvancesCreatedToAccepted_withHistory() {
    TacoOrder stored = order("o1", "userA", OrderStatus.CREATED);
    when(repo.findById("o1")).thenReturn(Mono.just(stored));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.transition("o1", OrderStatus.ACCEPTED,
            CallerIdentity.kitchen("cook-1"), "KITCHEN", "fired"))
        .expectNextMatches(saved ->
            saved.getStatus() == OrderStatus.ACCEPTED
            && saved.getStatusHistory().size() == 1
            && saved.getStatusHistory().get(0).getFrom() == OrderStatus.CREATED
            && saved.getStatusHistory().get(0).getTo() == OrderStatus.ACCEPTED
            && "KITCHEN".equals(saved.getStatusHistory().get(0).getOrigin()))
        .verifyComplete();
  }

  @Test
  public void tc25_repeatIsIdempotent_noSaveNoNewHistory() {
    TacoOrder stored = order("o1", "userA", OrderStatus.ACCEPTED);
    when(repo.findById("o1")).thenReturn(Mono.just(stored));

    StepVerifier.create(service.transition("o1", OrderStatus.ACCEPTED,
            CallerIdentity.kitchen("cook-1"), "KITCHEN", null))
        .expectNextMatches(saved ->
            saved.getStatus() == OrderStatus.ACCEPTED
            && saved.getStatusHistory().isEmpty())
        .verifyComplete();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc25_illegalJump_failsWithoutSaving() {
    when(repo.findById("o1"))
        .thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));

    StepVerifier.create(service.transition("o1", OrderStatus.DELIVERED,
            CallerIdentity.admin("boss"), "API", null))
        .expectError(OrderStatusTransitionException.class)
        .verify();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc25_userCannotMarkDelivered() {
    when(repo.findById("o1")).thenReturn(Mono.just(
        order("o1", "userA", OrderStatus.OUT_FOR_DELIVERY)));

    StepVerifier.create(service.transition("o1", OrderStatus.DELIVERED,
            CallerIdentity.user("userA"), "API", null))
        .expectError(OrderAccessDeniedException.class)
        .verify();
  }

  @Test
  public void tc25_ownerCancelsEarly() {
    when(repo.findById("o1"))
        .thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.cancel("o1", CallerIdentity.user("userA"), "changed mind"))
        .expectNextMatches(saved -> saved.getStatus() == OrderStatus.CANCELLED)
        .verifyComplete();
  }

  @Test
  public void tc25_ownerCannotCancelAfterKitchenStarted() {
    when(repo.findById("o1")).thenReturn(Mono.just(
        order("o1", "userA", OrderStatus.PREPARING)));

    StepVerifier.create(service.cancel("o1", CallerIdentity.user("userA"), "too late"))
        .expectError(OrderAccessDeniedException.class)
        .verify();
  }

  @Test
  public void tc25_adminCancelsLate() {
    when(repo.findById("o1")).thenReturn(Mono.just(
        order("o1", "userA", OrderStatus.PREPARING)));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.cancel("o1", CallerIdentity.admin("boss"), "recall"))
        .expectNextMatches(saved -> saved.getStatus() == OrderStatus.CANCELLED)
        .verifyComplete();
  }

  @Test
  public void tc25_missingOrder_isNotFound() {
    when(repo.findById("ghost")).thenReturn(Mono.empty());

    StepVerifier.create(service.transition("ghost", OrderStatus.ACCEPTED,
            CallerIdentity.admin("boss"), "API", null))
        .expectError(OrderNotFoundException.class)
        .verify();
  }

  @Test
  public void tc25_anonymous_isRejected() {
    when(repo.findById("o1"))
        .thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));

    StepVerifier.create(service.transition("o1", OrderStatus.ACCEPTED,
            CallerIdentity.anonymous(), "API", null))
        .expectError(AuthenticationRequiredException.class)
        .verify();
  }

  @Test
  public void tc25_staleWriter_propagatesOptimisticLock() {
    when(repo.findById("o1"))
        .thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));
    when(repo.save(any(TacoOrder.class))).thenReturn(
        Mono.error(new OptimisticLockingFailureException("stale version")));

    // The service lets the locking failure through; the HTTP layer maps it
    // to 409, which the controller test asserts end to end.
    StepVerifier.create(service.transition("o1", OrderStatus.ACCEPTED,
            CallerIdentity.kitchen("cook-1"), "KITCHEN", null))
        .expectError(OptimisticLockingFailureException.class)
        .verify();
  }

  @Test
  public void tc25_reasonTooLong_isRejected() {
    when(repo.findById("o1"))
        .thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));

    String longReason = "x".repeat(281);
    StepVerifier.create(service.transition("o1", OrderStatus.ACCEPTED,
            CallerIdentity.kitchen("cook-1"), "KITCHEN", longReason))
        .expectError(InvalidOrderStatusException.class)
        .verify();
  }

  @Test
  public void tc25_historyNeverCarriesSensitiveData() {
    TacoOrder stored = order("o1", "userA", OrderStatus.CREATED);
    when(repo.findById("o1")).thenReturn(Mono.just(stored));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.transition("o1", OrderStatus.ACCEPTED,
            CallerIdentity.kitchen("cook-1"), "KITCHEN", "ok"))
        .expectNextMatches(saved -> {
          String audit = saved.getStatusHistory().get(0).toString();
          return !audit.contains("4111") && !audit.contains("password");
        })
        .verifyComplete();
  }

  private static TacoOrder order(String id, String userId, OrderStatus status) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setPlacedAt(new Date());
    User user = new User("u", "pw", "U", "s", "c", "TX", "78701", "p", "u@t.co");
    user.setId(userId);
    order.setUser(user);
    order.setStatus(status);
    return order;
  }
}
