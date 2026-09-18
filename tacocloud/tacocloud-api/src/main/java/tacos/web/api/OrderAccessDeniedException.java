package tacos.web.api;

public class OrderAccessDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrderAccessDeniedException() {
    super("You are not allowed to operate on this order");
  }

}