package tacos;

/**
 * Lifecycle of a placed order (TC-25).
 *
 * <p>The order is no longer a document without a life story: it is created,
 * accepted by the kitchen, prepared, marked ready, sent out and delivered, or
 * cancelled. Every change goes through {@code OrderStatusPolicy} and
 * {@code OrderWorkflowService}, never by assigning this field from a request.
 */
public enum OrderStatus {
  CREATED,
  ACCEPTED,
  PREPARING,
  READY,
  OUT_FOR_DELIVERY,
  DELIVERED,
  CANCELLED
}
