package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;

/**
 * TC-26: the ETA is a deterministic function of the ticket and the queue.
 */
public class EtaCalculatorTest {

  private EtaCalculator calculator() {
    KitchenProperties props = new KitchenProperties();
    props.setBaseMinutes(5);
    props.setPerTacoMinutes(2);
    props.setPerIngredientMinutes(0.5);
    props.setPerQueuedOrderMinutes(1);
    return new EtaCalculator(props);
  }

  @Test
  public void tc26_etaGrowsWithQueueAndSize() {
    TacoOrder small = ticket(1, 2);
    TacoOrder big = ticket(4, 5);

    int smallEmpty = calculator().estimateMinutes(small, 0);
    int smallBusy = calculator().estimateMinutes(small, 6);
    int bigEmpty = calculator().estimateMinutes(big, 0);

    assertTrue(smallBusy > smallEmpty,
        "queue pressure must raise the estimate");
    assertTrue(bigEmpty > smallEmpty,
        "a bigger ticket must take longer than a small one");
  }

  @Test
  public void tc26_sameInputs_sameEta() {
    assertEquals(calculator().estimateMinutes(ticket(2, 3), 4),
        calculator().estimateMinutes(ticket(2, 3), 4));
  }

  @Test
  public void tc26_emptyTicket_isJustTheBase() {
    TacoOrder empty = new TacoOrder();
    empty.setTacos(List.of());
    assertEquals(5, calculator().estimateMinutes(empty, 0));
  }

  private static TacoOrder ticket(int tacos, int ingredientsPerTaco) {
    TacoOrder order = new TacoOrder();
    order.setPlacedAt(new Date());
    for (int t = 0; t < tacos; t++) {
      Taco taco = new Taco();
      taco.setName("Taco " + t);
      for (int i = 0; i < ingredientsPerTaco; i++) {
        taco.setIngredients(taco.getIngredients() == null
            ? new java.util.ArrayList<>()
            : taco.getIngredients());
        taco.getIngredients().add(
            new Ingredient("ID" + i, "Ing " + i, Ingredient.Type.CHEESE));
      }
      order.addTaco(taco);
    }
    return order;
  }
}
