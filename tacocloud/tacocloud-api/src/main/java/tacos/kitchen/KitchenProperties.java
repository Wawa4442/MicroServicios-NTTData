package tacos.kitchen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Knobs of the kitchen queue (TC-26).
 *
 * <p>The ETA is an estimate, not a promise: every term is configuration so
 * the kitchen can tune it without a rebuild, and the same inputs always give
 * the same number of minutes.
 */
@Component
@ConfigurationProperties(prefix = "tacos.kitchen")
public class KitchenProperties {

  /**
   * Fixed cost of firing up a ticket, in minutes.
   */
  private int baseMinutes = 5;

  /**
   * Extra minutes per taco on the ticket.
   */
  private int perTacoMinutes = 2;

  /**
   * Extra minutes per ingredient across the ticket (complexity proxy).
   */
  private double perIngredientMinutes = 0.5;

  /**
   * Extra minutes per order already waiting ahead (queue pressure).
   */
  private int perQueuedOrderMinutes = 1;

  private int defaultPageSize = 20;

  private int maxPageSize = 100;

  public int getBaseMinutes() {
    return baseMinutes;
  }

  public void setBaseMinutes(int baseMinutes) {
    this.baseMinutes = baseMinutes;
  }

  public int getPerTacoMinutes() {
    return perTacoMinutes;
  }

  public void setPerTacoMinutes(int perTacoMinutes) {
    this.perTacoMinutes = perTacoMinutes;
  }

  public double getPerIngredientMinutes() {
    return perIngredientMinutes;
  }

  public void setPerIngredientMinutes(double perIngredientMinutes) {
    this.perIngredientMinutes = perIngredientMinutes;
  }

  public int getPerQueuedOrderMinutes() {
    return perQueuedOrderMinutes;
  }

  public void setPerQueuedOrderMinutes(int perQueuedOrderMinutes) {
    this.perQueuedOrderMinutes = perQueuedOrderMinutes;
  }

  public int getDefaultPageSize() {
    return defaultPageSize;
  }

  public void setDefaultPageSize(int defaultPageSize) {
    this.defaultPageSize = defaultPageSize;
  }

  public int getMaxPageSize() {
    return maxPageSize;
  }

  public void setMaxPageSize(int maxPageSize) {
    this.maxPageSize = maxPageSize;
  }
}
