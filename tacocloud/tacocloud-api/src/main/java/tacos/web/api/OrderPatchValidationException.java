package tacos.web.api;

public class OrderPatchValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrderPatchValidationException(String reason) {
    super(reason);
  }

}