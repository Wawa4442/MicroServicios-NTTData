package tacos;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A sellable resource, not just a name/type pair. The catalog fields
 * ({@code unitPrice}, {@code available}, {@code stockOnHand}) let the system
 * price, reserve and decide. The dietary metadata is data about the
 * ingredient; the classification of a taco is a derived policy (TC-17).
 *
 * <p>Money is {@link BigDecimal}: never {@code double}. The {@link Version}
 * field enables optimistic locking so concurrent stock updates do not clobber
 * each other.
 */
@Data
@NoArgsConstructor(access=AccessLevel.PRIVATE, force=true)
@Document
public class Ingredient {

  @Id
  private String id;

  @NotNull
  private String name;

  @NotNull
  private Type type;

  @NotNull
  @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
  private BigDecimal unitPrice = BigDecimal.ZERO;

  /** Commercial pause: {@code false} hides the ingredient from sale. */
  private boolean available = true;

  @Min(value = 0, message = "stockOnHand must not be negative")
  private int stockOnHand = 0;

  @Min(value = 0, message = "reorderLevel must not be negative")
  private int reorderLevel = 0;

  private Set<DietaryTag> dietaryTags = EnumSet.noneOf(DietaryTag.class);

  private Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);

  private SpiceLevel spice = SpiceLevel.NONE;

  @Version
  private Long version;

  public enum Type {
    WRAP, PROTEIN, VEGGIES, CHEESE, SAUCE
  }

  /** Convenience constructor used by tests and seeds that only need identity. */
  public Ingredient(String id, String name, Type type) {
    this.id = id;
    this.name = name;
    this.type = type;
  }

  /** Full constructor for catalog seeds and fixtures. */
  public Ingredient(String id, String name, Type type, BigDecimal unitPrice,
                    boolean available, int stockOnHand, int reorderLevel) {
    this.id = id;
    this.name = name;
    this.type = type;
    this.unitPrice = unitPrice;
    this.available = available;
    this.stockOnHand = stockOnHand;
    this.reorderLevel = reorderLevel;
  }

}