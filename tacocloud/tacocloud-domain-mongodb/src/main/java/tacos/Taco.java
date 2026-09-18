package tacos;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.rest.core.annotation.RestResource;

import lombok.Data;

@Data
@RestResource(rel = "tacos", path = "tacos")
@Document
public class Taco {

  @Id
  private String id;
  
  @NotNull
  @Size(min = 5, message = "Name must be at least 5 characters long")
  private String name;
  
  private Date createdAt = new Date();
  
  @Size(min=1, message="You must choose at least 1 ingredient")
  private List<Ingredient> ingredients;

  // ------------------------------------------------------------------
  // Order-line snapshot (TC-14). These fields are meaningful only when the
  // taco is embedded in an order: they freeze how many units were bought and
  // the price at purchase time, so later catalog changes never rewrite
  // historical orders. Server-owned: never accepted from the client.
  // ------------------------------------------------------------------

  private int quantity = 1;

  private BigDecimal unitPriceAtPurchase;

  private BigDecimal subtotal;

}
