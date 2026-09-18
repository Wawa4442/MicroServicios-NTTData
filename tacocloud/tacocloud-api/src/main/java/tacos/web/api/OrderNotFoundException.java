package tacos.web.api;

public class OrderNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrderNotFoundException(String orderId) {
    super("Order does not exist: " + orderId);
  }

}