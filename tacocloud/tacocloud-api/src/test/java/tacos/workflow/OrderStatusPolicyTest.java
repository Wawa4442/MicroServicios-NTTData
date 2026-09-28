package tacos.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tacos.OrderStatus;
import tacos.web.api.AuthenticationRequiredException;
import tacos.web.api.CallerIdentity;
import tacos.web.api.OrderAccessDeniedException;

/**
 * TC-25: the transition matrix and the role rules, without any database.
 */
public class OrderStatusPolicyTest {

  static Stream<Arguments> happyPath() {
    return Stream.of(
        Arguments.of(OrderStatus.CREATED, OrderStatus.ACCEPTED),
        Arguments.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING),
        Arguments.of(OrderStatus.PREPARING, OrderStatus.READY),
        Arguments.of(OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY),
        Arguments.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED));
  }

  @ParameterizedTest
  @MethodSource("happyPath")
  public void tc25_forwardMoves_areAllowed(OrderStatus from, OrderStatus to) {
    assertTrue(OrderStatusPolicy.isAllowed(from, to));
  }

  @Test
  public void tc25_createdCannotJumpToDelivered() {
    assertFalse(OrderStatusPolicy.isAllowed(
        OrderStatus.CREATED, OrderStatus.DELIVERED));
  }

  @Test
  public void tc25_terminalStates_haveNoOutgoingMoves() {
    assertTrue(OrderStatusPolicy.allowedFrom(OrderStatus.DELIVERED).isEmpty());
    assertTrue(OrderStatusPolicy.allowedFrom(OrderStatus.CANCELLED).isEmpty());
  }

  @Test
  public void tc25_ownerMayCancelEarly_onlyBeforePreparing() {
    assertTrue(OrderStatusPolicy.ownerMayCancel(OrderStatus.CREATED));
    assertTrue(OrderStatusPolicy.ownerMayCancel(OrderStatus.ACCEPTED));
    assertFalse(OrderStatusPolicy.ownerMayCancel(OrderStatus.PREPARING));
    assertFalse(OrderStatusPolicy.ownerMayCancel(OrderStatus.DELIVERED));
  }

  @Test
  public void tc25_kitchenMovesForward_butNeverCancels() {
    CallerIdentity kitchen = CallerIdentity.kitchen("cook-1");
    // Forward: allowed.
    OrderStatusPolicy.check(OrderStatus.CREATED, OrderStatus.ACCEPTED,
        kitchen, false);
    // Cancel by the kitchen: forbidden even though the matrix allows it.
    assertThrows(OrderAccessDeniedException.class, () ->
        OrderStatusPolicy.check(OrderStatus.CREATED, OrderStatus.CANCELLED,
            kitchen, false));
  }

  @Test
  public void tc25_plainCustomer_cannotMarkDelivered() {
    CallerIdentity alice = CallerIdentity.user("userA");
    assertThrows(OrderAccessDeniedException.class, () ->
        OrderStatusPolicy.check(OrderStatus.OUT_FOR_DELIVERY,
            OrderStatus.DELIVERED, alice, true));
  }

  @Test
  public void tc25_ownerCancelsEarly_ownOrder() {
    CallerIdentity alice = CallerIdentity.user("userA");
    OrderStatusPolicy.check(OrderStatus.CREATED, OrderStatus.CANCELLED,
        alice, true);
    OrderStatusPolicy.check(OrderStatus.ACCEPTED, OrderStatus.CANCELLED,
        alice, true);
  }

  @Test
  public void tc25_ownerCannotCancelSomebodyElsesOrder() {
    CallerIdentity alice = CallerIdentity.user("userA");
    assertThrows(OrderAccessDeniedException.class, () ->
        OrderStatusPolicy.check(OrderStatus.CREATED, OrderStatus.CANCELLED,
            alice, false));
  }

  @Test
  public void tc25_ownerCannotCancelAfterKitchenStarted() {
    CallerIdentity alice = CallerIdentity.user("userA");
    assertThrows(OrderAccessDeniedException.class, () ->
        OrderStatusPolicy.check(OrderStatus.PREPARING, OrderStatus.CANCELLED,
            alice, true));
  }

  @Test
  public void tc25_adminMayRunAnyLegalMove() {
    CallerIdentity admin = CallerIdentity.admin("boss");
    OrderStatusPolicy.check(OrderStatus.PREPARING, OrderStatus.CANCELLED,
        admin, false);
    OrderStatusPolicy.check(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED,
        admin, false);
  }

  @Test
  public void tc25_anonymousMustSignIn() {
    assertThrows(AuthenticationRequiredException.class, () ->
        OrderStatusPolicy.check(OrderStatus.CREATED, OrderStatus.ACCEPTED,
            CallerIdentity.anonymous(), true));
  }

  @Test
  public void tc25_illegalJump_isTransitionError_notForbidden() {
    CallerIdentity admin = CallerIdentity.admin("boss");
    assertThrows(OrderStatusTransitionException.class, () ->
        OrderStatusPolicy.check(OrderStatus.CREATED, OrderStatus.DELIVERED,
            admin, true));
  }

  @Test
  public void tc25_repeatIsIdempotentByContract() {
    // Same status twice is not a move at all: check() returns quietly.
    CallerIdentity alice = CallerIdentity.user("userA");
    OrderStatusPolicy.check(OrderStatus.CREATED, OrderStatus.CREATED,
        alice, true);
    assertEquals(OrderStatus.CREATED, OrderStatus.CREATED);
  }
}
