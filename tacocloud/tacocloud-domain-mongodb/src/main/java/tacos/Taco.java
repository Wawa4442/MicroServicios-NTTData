package tacos;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.rest.core.annotation.RestResource;

import lombok.Data;

/**
 * A taco design as published in the catalog. Search (TC-19) filters and sorts
 * on these fields, so the indexes that back those operations are declared here
 * next to the fields they serve instead of being a separate migration file
 * nobody remembers to update.
 */
@Data
@RestResource(rel = "tacos", path = "tacos")
@Document
@CompoundIndexes({
    // "tacos containing ingredient X, newest first" is the hot query of the
    // catalog browser; the trailing _id keeps paging stable when two tacos
    // share a createdAt.
    @CompoundIndex(name = "taco_ingredient_created_idx",
        def = "{'ingredients._id': 1, 'createdAt': -1, '_id': 1}")
})
public class Taco {

  @Id
  private String id;

  @NotNull
  @Size(min = 5, message = "Name must be at least 5 characters long")
  @Indexed
  private String name;

  @Indexed
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
