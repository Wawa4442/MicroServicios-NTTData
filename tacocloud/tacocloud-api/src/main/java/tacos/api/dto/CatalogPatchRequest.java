package tacos.api.dto;

import java.math.BigDecimal;

import javax.validation.constraints.DecimalMin;

import lombok.Data;

/**
 * Operator patch for the commercial attributes of an ingredient. At least one
 * field must be present; the service enforces that rule and the catalog
 * invariants.
 */
@Data
public class CatalogPatchRequest {

  @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
  private BigDecimal unitPrice;

  private Boolean available;

  public boolean isEmpty() {
    return unitPrice == null && available == null;
  }

}