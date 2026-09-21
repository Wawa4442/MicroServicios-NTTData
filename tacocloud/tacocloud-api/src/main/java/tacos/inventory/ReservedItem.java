package tacos.inventory;

import lombok.Value;

/**
 * One reserved unit group inside a {@link StockReservation}: an ingredient id
 * and the total quantity required by the order for that ingredient (aggregated
 * across all taco lines and quantities).
 */
@Value
public class ReservedItem {

  private final String ingredientId;
  private final int quantity;

  public static ReservedItem of(String ingredientId, int quantity) {
    return new ReservedItem(ingredientId, quantity);
  }

}