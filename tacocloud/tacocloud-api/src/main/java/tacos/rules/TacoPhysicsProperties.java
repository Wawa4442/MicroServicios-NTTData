package tacos.rules;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * External configuration for the Taco Physics rules (TC-18). Ingredient ids
 * referenced by the "fun" rules are never hardcoded in code: they live here so
 * a catalog change only touches configuration.
 */
@Component
@ConfigurationProperties(prefix = "tacos.physics")
@Data
public class TacoPhysicsProperties {

  private int minIngredients = 2;

  private int maxIngredients = 12;

  /** The id of the very hot pepper ingredient mentioned by the fun rule. */
  private String ghostPepperIngredientId = "GHPR";

  /** Beverage ids that can tame a Ghost Pepper taco. */
  private List<String> drinkIngredientIds = new ArrayList<>();

  private boolean veganNoMeatEnabled = true;

}