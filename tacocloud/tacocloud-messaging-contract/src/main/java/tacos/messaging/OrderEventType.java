package tacos.messaging;

/**
 * The three facts the kitchen cares about (TC-27).
 *
 * <p>New types must be added, never renamed or reused: a v1 consumer ignores
 * what it does not know, but it cannot unlearn a changed meaning.
 */
public enum OrderEventType {
  ORDER_CREATED,
  STATUS_CHANGED,
  ORDER_CANCELLED
}
