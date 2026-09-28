package tacos.web.api;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import tacos.catalog.IngredientCatalogValidationException;
import tacos.catalog.StockAdjustmentRejectedException;
import tacos.coupon.CouponNotApplicableException;
import tacos.inventory.InsufficientStockException;
import tacos.paging.InvalidPageBoundsException;
import tacos.pricing.InvalidQuantityException;
import tacos.rating.InvalidRatingException;
import tacos.recommendation.NoTacoOfTheDayException;
import tacos.reorder.ReorderPaymentMethodRequiredException;
import tacos.rules.RuleViolation;
import tacos.rules.TacoDesignInvalidException;
import tacos.search.InvalidTacoSearchException;
import tacos.web.api.ApiProblem.Violation;
import tacos.kitchen.KitchenQueueEmptyException;
import tacos.workflow.InvalidOrderStatusException;
import tacos.workflow.OrderStatusTransitionException;

@RestControllerAdvice
public class RestProblemHandler {

  @ExceptionHandler(WebExchangeBindException.class)
  public ResponseEntity<ApiProblem> handleValidation(
      WebExchangeBindException e, ServerWebExchange exchange) {
    List<Violation> violations = e.getBindingResult().getFieldErrors().stream()
        .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
        .collect(Collectors.toList());
    return problem(HttpStatus.BAD_REQUEST, "Validation failed", "validation_error",
        "The request body does not satisfy the schema constraints.", violations, exchange);
  }

  /**
   * A body that could not be read at all: truncated JSON, a wrong type for a
   * field, or no body on a route that requires one.
   *
   * <p>This is a client mistake, so it is a 400 with a stable code. Letting it
   * fall through to the catch-all would answer 500 and make an unescaped
   * newline in a request look like an outage. Schema violations are a subclass
   * of this and keep their own handler with the field-level detail.
   */
  @ExceptionHandler(ServerWebInputException.class)
  public ResponseEntity<ApiProblem> handleUnreadableBody(
      ServerWebInputException e, ServerWebExchange exchange) {
    return problem(HttpStatus.BAD_REQUEST, "Unreadable request", "malformed_request",
        "The request body could not be read as the expected content type.", exchange);
  }

  @ExceptionHandler(OrderPatchValidationException.class)
  public ResponseEntity<ApiProblem> handlePatchValidation(
      OrderPatchValidationException e, ServerWebExchange exchange) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid patch", "validation_error",
        e.getMessage(), exchange);
  }

  @ExceptionHandler(UnknownPaymentMethodException.class)
  public ResponseEntity<ApiProblem> handleUnknownPaymentMethod(
      UnknownPaymentMethodException e, ServerWebExchange exchange) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid payment method",
        "invalid_payment_method", e.getMessage(), exchange);
  }

  @ExceptionHandler(UnknownIngredientException.class)
  public ResponseEntity<ApiProblem> handleUnknownIngredient(
      UnknownIngredientException e, ServerWebExchange exchange) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown ingredient",
        "unknown_ingredient", e.getMessage(), exchange);
  }

  @ExceptionHandler(StockAdjustmentRejectedException.class)
  public ResponseEntity<ApiProblem> handleStockAdjustment(
      StockAdjustmentRejectedException e, ServerWebExchange exchange) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient stock",
        "insufficient_stock", e.getMessage(), exchange);
  }

  @ExceptionHandler(InsufficientStockException.class)
  public ResponseEntity<ApiProblem> handleInsufficientStock(
      InsufficientStockException e, ServerWebExchange exchange) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient stock",
        "insufficient_stock", e.getMessage(), exchange);
  }

  @ExceptionHandler(CouponNotApplicableException.class)
  public ResponseEntity<ApiProblem> handleCouponNotApplicable(
      CouponNotApplicableException e, ServerWebExchange exchange) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Coupon not applicable",
        "coupon_not_applicable", e.getStatus().name() + ": " + e.getMessage(), exchange);
  }

  @ExceptionHandler(TacoDesignInvalidException.class)
  public ResponseEntity<ApiProblem> handleTacoDesignInvalid(
      TacoDesignInvalidException e, ServerWebExchange exchange) {
    List<Violation> violations = e.getViolations().stream()
        .map(v -> new Violation(v.getCode(), v.getMessage()))
        .collect(Collectors.toList());
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid taco design",
        "taco_design_invalid", "The taco design violates one or more rules.",
        violations, exchange);
  }

  @ExceptionHandler(IngredientCatalogValidationException.class)
  public ResponseEntity<ApiProblem> handleCatalogValidation(
      IngredientCatalogValidationException e, ServerWebExchange exchange) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid catalog data",
        "ingredient_catalog_invalid", e.getMessage(), exchange);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ApiProblem> handleOptimisticLock(
      OptimisticLockingFailureException e, ServerWebExchange exchange) {
    return problem(HttpStatus.CONFLICT, "Conflict", "conflict",
        "The resource was modified concurrently; reload and retry.", exchange);
  }

  @ExceptionHandler(InvalidQuantityException.class)
  public ResponseEntity<ApiProblem> handleInvalidQuantity(
      InvalidQuantityException e, ServerWebExchange exchange) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid quantity",
        "invalid_quantity", e.getMessage(), exchange);
  }

  @ExceptionHandler(OrderNotFoundException.class)
  public ResponseEntity<ApiProblem> handleNotFound(
      OrderNotFoundException e, ServerWebExchange exchange) {
    return problem(HttpStatus.NOT_FOUND, "Order not found", "order_not_found",
        e.getMessage(), exchange);
  }

  @ExceptionHandler(OrderAccessDeniedException.class)
  public ResponseEntity<ApiProblem> handleAccessDenied(
      OrderAccessDeniedException e, ServerWebExchange exchange) {
    return problem(HttpStatus.FORBIDDEN, "Access denied", "access_denied",
        "The caller is not entitled to perform this operation on the order.", exchange);
  }

  @ExceptionHandler(EmailOrderConversionException.class)
  public ResponseEntity<ApiProblem> handleEmailConversion(
      EmailOrderConversionException e, ServerWebExchange exchange) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Order rejected",
        "order_rejected", e.getMessage(), exchange);
  }

  // ------------------------------------------------------------------
  // Laboratorio 4
  // ------------------------------------------------------------------

  @ExceptionHandler(InvalidPageBoundsException.class)
  public ResponseEntity<ApiProblem> handleInvalidPageBounds(
      InvalidPageBoundsException e, ServerWebExchange exchange) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid pagination", "invalid_page",
        e.getMessage(), exchange);
  }

  @ExceptionHandler(InvalidTacoSearchException.class)
  public ResponseEntity<ApiProblem> handleInvalidSearch(
      InvalidTacoSearchException e, ServerWebExchange exchange) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid catalog search",
        "invalid_search", e.getMessage(), exchange);
  }

  @ExceptionHandler(InvalidRatingException.class)
  public ResponseEntity<ApiProblem> handleInvalidRating(
      InvalidRatingException e, ServerWebExchange exchange) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid rating", "invalid_rating",
        e.getMessage(), exchange);
  }

  @ExceptionHandler(AuthenticationRequiredException.class)
  public ResponseEntity<ApiProblem> handleAuthenticationRequired(
      AuthenticationRequiredException e, ServerWebExchange exchange) {
    return problem(HttpStatus.UNAUTHORIZED, "Authentication required",
        "authentication_required", e.getMessage(), exchange);
  }

  @ExceptionHandler(NoTacoOfTheDayException.class)
  public ResponseEntity<ApiProblem> handleNoTacoOfTheDay(
      NoTacoOfTheDayException e, ServerWebExchange exchange) {
    return problem(HttpStatus.NOT_FOUND, "No taco of the day",
        "no_taco_of_the_day", e.getMessage(), exchange);
  }

  @ExceptionHandler(TacoNotFoundException.class)
  public ResponseEntity<ApiProblem> handleTacoNotFound(
      TacoNotFoundException e, ServerWebExchange exchange) {
    return problem(HttpStatus.NOT_FOUND, "Taco not found", "taco_not_found",
        e.getMessage(), exchange);
  }

  @ExceptionHandler(ReorderPaymentMethodRequiredException.class)
  public ResponseEntity<ApiProblem> handleReorderPaymentMethod(
      ReorderPaymentMethodRequiredException e, ServerWebExchange exchange) {
    return problem(HttpStatus.BAD_REQUEST, "Payment method required",
        "reorder_payment_method_required", e.getMessage(), exchange);
  }

  // ------------------------------------------------------------------
  // Laboratorio 5 — cocina y mensajeria confiable
  // ------------------------------------------------------------------

  @ExceptionHandler(OrderStatusTransitionException.class)
  public ResponseEntity<ApiProblem> handleStatusTransition(
      OrderStatusTransitionException e, ServerWebExchange exchange) {
    return problem(HttpStatus.CONFLICT, "Invalid status transition",
        "invalid_status_transition", e.getMessage(), exchange);
  }

  @ExceptionHandler(InvalidOrderStatusException.class)
  public ResponseEntity<ApiProblem> handleInvalidStatus(
      InvalidOrderStatusException e, ServerWebExchange exchange) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid status",
        "invalid_status", e.getMessage(), exchange);
  }

  @ExceptionHandler(KitchenQueueEmptyException.class)
  public ResponseEntity<ApiProblem> handleQueueEmpty(
      KitchenQueueEmptyException e, ServerWebExchange exchange) {
    return problem(HttpStatus.NOT_FOUND, "Kitchen queue empty",
        "kitchen_queue_empty", e.getMessage(), exchange);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<ApiProblem> handleConflict(
      DuplicateKeyException e, ServerWebExchange exchange) {
    return problem(HttpStatus.CONFLICT, "Conflict", "conflict",
        "The request conflicts with the current state of the resource.", exchange);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiProblem> handleGeneric(
      Exception e, ServerWebExchange exchange) {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
        "internal_error", "An unexpected error occurred.", exchange);
  }

  private ResponseEntity<ApiProblem> problem(HttpStatus status, String title, String code,
                                             String detail, ServerWebExchange exchange) {
    return problem(status, title, code, detail, List.of(), exchange);
  }

  private ResponseEntity<ApiProblem> problem(HttpStatus status, String title, String code,
      String detail, List<Violation> violations, ServerWebExchange exchange) {
    ApiProblem body = ApiProblem.of("urn:tacocloud:problem:" + code, title,
        status.value(), detail, exchange.getRequest().getPath().value(), code, violations);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}