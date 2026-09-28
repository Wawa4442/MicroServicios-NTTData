package tacos.api.dto;

import java.math.BigDecimal;

import lombok.Value;

import tacos.Taco;

/**
 * One entry of {@code GET /api/tacos/top} (TC-22). The taco name is resolved
 * from the catalog, so the chart shows what a customer can actually order
 * rather than a bare id.
 */
@Value
public class TopTacoResponse {

  private final String tacoId;
  private final String name;
  private final BigDecimal averageScore;
  private final int votes;

  public static TopTacoResponse of(String tacoId, Taco taco, BigDecimal averageScore, int votes) {
    return new TopTacoResponse(tacoId, taco == null ? null : taco.getName(), averageScore, votes);
  }

}
