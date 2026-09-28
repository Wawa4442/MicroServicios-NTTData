package tacos.kitchen;

import org.springframework.stereotype.Component;

import tacos.TacoOrder;

/**
 * Deterministic preparation estimate (TC-26).
 *
 * <p>{@code base + perTaco * tacos + perIngredient * ingredients + perQueued *
 * queueDepth}, rounded up. Same ticket and same queue depth always give the
 * same number: it is a planning hint for the kitchen display, never a
 * contractual promise to the customer.
 */
@Component
public class EtaCalculator {

  private final KitchenProperties props;

  public EtaCalculator(KitchenProperties props) {
    this.props = props;
  }

  public int estimateMinutes(TacoOrder order, long queueDepth) {
    int tacos = order.getTacos() == null ? 0 : order.getTacos().size();
    int ingredients = 0;
    if (order.getTacos() != null) {
      for (tacos.Taco taco : order.getTacos()) {
        if (taco != null && taco.getIngredients() != null) {
          ingredients += taco.getIngredients().size();
        }
      }
    }
    double total = props.getBaseMinutes()
        + props.getPerTacoMinutes() * (double) tacos
        + props.getPerIngredientMinutes() * (double) ingredients
        + props.getPerQueuedOrderMinutes() * (double) Math.max(0, queueDepth);
    return (int) Math.ceil(total);
  }
}
