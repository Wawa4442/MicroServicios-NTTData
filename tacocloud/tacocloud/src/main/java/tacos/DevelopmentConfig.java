package tacos;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.payment.PaymentGateway;

@Profile("!prod")
@Configuration
public class DevelopmentConfig {

  @Bean
  public CommandLineRunner dataLoader(IngredientRepository repo,
        UserRepository userRepo, PasswordEncoder encoder, TacoRepository tacoRepo,
        PaymentMethodRepository paymentMethodRepo,
        PaymentGateway paymentGateway) { // user repo for ease of testing with a built-in user
    
    return new CommandLineRunner() {
      @Override
      public void run(String... args) throws Exception {
        Ingredient flourTortilla = saveAnIngredient("FLTO", "Flour Tortilla", Type.WRAP, "0.75",
            EnumSet.of(DietaryTag.VEGAN, DietaryTag.VEGETARIAN),
            EnumSet.of(Allergen.GLUTEN), SpiceLevel.NONE);
        Ingredient cornTortilla = saveAnIngredient("COTO", "Corn Tortilla", Type.WRAP, "0.65",
            EnumSet.of(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.noneOf(Allergen.class), SpiceLevel.NONE);
        Ingredient groundBeef = saveAnIngredient("GRBF", "Ground Beef", Type.PROTEIN, "2.50",
            EnumSet.noneOf(DietaryTag.class), EnumSet.of(Allergen.MEAT), SpiceLevel.NONE);
        Ingredient carnitas = saveAnIngredient("CARN", "Carnitas", Type.PROTEIN, "2.75",
            EnumSet.noneOf(DietaryTag.class), EnumSet.of(Allergen.MEAT), SpiceLevel.NONE);
        Ingredient tomatoes = saveAnIngredient("TMTO", "Diced Tomatoes", Type.VEGGIES, "0.50",
            EnumSet.of(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.noneOf(Allergen.class), SpiceLevel.NONE);
        Ingredient lettuce = saveAnIngredient("LETC", "Lettuce", Type.VEGGIES, "0.40",
            EnumSet.of(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.noneOf(Allergen.class), SpiceLevel.NONE);
        Ingredient cheddar = saveAnIngredient("CHED", "Cheddar", Type.CHEESE, "0.90",
            EnumSet.of(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.of(Allergen.DAIRY), SpiceLevel.NONE);
        Ingredient jack = saveAnIngredient("JACK", "Monterrey Jack", Type.CHEESE, "0.95",
            EnumSet.of(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.of(Allergen.DAIRY), SpiceLevel.NONE);
        Ingredient salsa = saveAnIngredient("SLSA", "Salsa", Type.SAUCE, "0.30",
            EnumSet.of(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.noneOf(Allergen.class), SpiceLevel.MILD);
        Ingredient sourCream = saveAnIngredient("SRCR", "Sour Cream", Type.SAUCE, "0.45",
            EnumSet.of(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.of(Allergen.DAIRY), SpiceLevel.NONE);
        // Fun-rule ingredients (TC-18): referenced from tacos.physics config,
        // never from code.
        Ingredient ghostPepper = saveAnIngredient("GHPR", "Ghost Pepper", Type.VEGGIES, "1.20",
            EnumSet.of(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.noneOf(Allergen.class), SpiceLevel.EXTRA_HOT);
        Ingredient horchata = saveAnIngredient("WATR", "Horchata", Type.VEGGIES, "0.20",
            EnumSet.of(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            EnumSet.noneOf(Allergen.class), SpiceLevel.NONE);

//        UserUDT u = new UserUDT(username, fullname, phoneNumber)
        
        User habuma = new User("habuma", encoder.encode("password"), 
              "Craig Walls", "123 North Street", "Cross Roads", "TX", 
              "76227", "123-123-1234", "craig@habuma.com");
        habuma.setRole("ROLE_ADMIN");
        userRepo.save(habuma)
          .flatMap(user -> paymentGateway.tokenize("4111111111111111", "321", "10/25")
              .flatMap(card -> paymentMethodRepo.save(
                  new PaymentMethod(user, card.getPaymentToken(),
                      card.getBrand(), card.getLast4(), card.getExpiration()))))
          .subscribe();        
        
        Taco taco1 = new Taco();
        taco1.setId("TACO1");
        taco1.setName("Carnivore");
        taco1.setIngredients(Arrays.asList(flourTortilla, groundBeef, carnitas, sourCream, salsa, cheddar));
        tacoRepo.save(taco1).subscribe();

        Taco taco2 = new Taco();
        taco2.setId("TACO2");
        taco2.setName("Bovine Bounty");
        taco2.setIngredients(Arrays.asList(cornTortilla, groundBeef, cheddar, jack, sourCream));
        tacoRepo.save(taco2).subscribe();

        Taco taco3 = new Taco();
        taco3.setId("TACO3");
        taco3.setName("Veg-Out");
        taco3.setIngredients(Arrays.asList(flourTortilla, cornTortilla, tomatoes, lettuce, salsa));
        tacoRepo.save(taco3).subscribe();

        Taco taco4 = new Taco();
        taco4.setId("TACO4");
        taco4.setName("Ghost Rider");
        taco4.setIngredients(Arrays.asList(flourTortilla, ghostPepper, tomatoes, salsa, horchata));
        tacoRepo.save(taco4).subscribe();

      }

      private Ingredient saveAnIngredient(String id, String name, Type type, String price,
          EnumSet<DietaryTag> dietaryTags, EnumSet<Allergen> allergens, SpiceLevel spice) {
        Ingredient ingredient = new Ingredient(id, name, type, new BigDecimal(price),
            true, 100, 20);
        ingredient.setDietaryTags(dietaryTags);
        ingredient.setAllergens(allergens);
        ingredient.setSpice(spice);
        repo.save(ingredient).subscribe();
        return ingredient;
      }
    };
  }
  
}