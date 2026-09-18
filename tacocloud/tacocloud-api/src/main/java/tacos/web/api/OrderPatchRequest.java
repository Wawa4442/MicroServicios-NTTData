package tacos.web.api;

import lombok.Data;

@Data
public class OrderPatchRequest {

  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;

}
