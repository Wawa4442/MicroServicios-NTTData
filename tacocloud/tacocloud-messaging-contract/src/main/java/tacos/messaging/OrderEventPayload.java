package tacos.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the kitchen needs to cook, and nothing it must not see (TC-27).
 *
 * <p>A snapshot, not a reference: taco names, counts, totals and the city or
 * state for routing. Never the street, ZIP, payment reference, user object,
 * password or card data. A consumer that cannot resolve a reference is not
 * helped by receiving one; a consumer that receives card data is a breach
 * waiting for a log file.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderEventPayload {

  private String orderId;
  private String status;
  private String previousStatus;
  private int tacoCount;
  private List<String> tacoNames = new ArrayList<>();
  private String currency;
  private BigDecimal total;
  private Instant placedAt;
  private String deliveryCity;
  private String deliveryState;
  private String stationId;
}
