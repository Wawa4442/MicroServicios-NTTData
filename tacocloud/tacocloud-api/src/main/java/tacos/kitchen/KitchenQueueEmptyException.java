package tacos.kitchen;

/**
 * The queue has no waiting ticket (TC-26). Mapped to 404 with the stable
 * code {@code kitchen_queue_empty} so the kitchen display can show "all
 * caught up" instead of treating it as an outage.
 */
public class KitchenQueueEmptyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public KitchenQueueEmptyException() {
    super("There is no order waiting in the kitchen queue.");
  }
}
