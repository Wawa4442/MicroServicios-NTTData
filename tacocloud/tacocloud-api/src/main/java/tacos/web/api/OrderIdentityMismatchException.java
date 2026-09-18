package tacos.web.api;

public class OrderIdentityMismatchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrderIdentityMismatchException() {
    super("Order ID in the body does not match the ID in the path");
  }

}