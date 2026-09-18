package tacos.web.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.PaymentMethod;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.pricing.PricingProperties;
import tacos.pricing.PricingService;
import tacos.web.api.EmailOrder.EmailTaco;

public class EmailOrderServiceTest {

  private UserRepository userRepo;
  private IngredientRepository ingredientRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private EmailOrderService service;

  private static final User HABUMA = habuma();
  private static final PaymentMethod CARD = card();

  private static final Ingredient FLTO =
      new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
  private static final Ingredient GRBF =
      new Ingredient("GRBF", "Ground Beef", Type.PROTEIN);
  private static final Ingredient CHED =
      new Ingredient("CHED", "Cheddar", Type.CHEESE);

  @BeforeEach
  public void setup() {
    userRepo = Mockito.mock(UserRepository.class);
    ingredientRepo = Mockito.mock(IngredientRepository.class);
    paymentMethodRepo = Mockito.mock(PaymentMethodRepository.class);
    service = new EmailOrderService(userRepo, ingredientRepo, paymentMethodRepo,
        new PricingService(new PricingProperties()));

    when(userRepo.findByEmail("craig@habuma.com")).thenReturn(Mono.just(HABUMA));
    when(paymentMethodRepo.findByUserId("user1")).thenReturn(Mono.just(CARD));
  }

  @Test
  public void tc06_happyPath_withSeveralTacos_emitsCompleteOrder() {
    stubIngredients(FLTO, GRBF, CHED);

    EmailOrder email = new EmailOrder();
    email.setEmail("craig@habuma.com");
    email.setTacos(Arrays.asList(
        taco("Carnivore", "FLTO", "GRBF"),
        taco("Veg-Out", "FLTO", "CHED")));

    StepVerifier.create(service.convertEmailOrderToDomainOrder(Mono.just(email)))
        .assertNext(order -> {
          org.junit.jupiter.api.Assertions.assertEquals("Craig Walls", order.getDeliveryName());
          org.junit.jupiter.api.Assertions.assertEquals("craig@habuma.com", order.getUser().getEmail());
          org.junit.jupiter.api.Assertions.assertEquals("pm-1", order.getPaymentMethodId(),
              "the order only records the opaque payment method id, never card data");
          org.junit.jupiter.api.Assertions.assertEquals(2, order.getTacos().size());
          org.junit.jupiter.api.Assertions.assertEquals("Carnivore", order.getTacos().get(0).getName());
          org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(FLTO, GRBF),
              order.getTacos().get(0).getIngredients());
          org.junit.jupiter.api.Assertions.assertEquals("Veg-Out", order.getTacos().get(1).getName());
          org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(FLTO, CHED),
              order.getTacos().get(1).getIngredients());
        })
        .verifyComplete();
  }

  @Test
  public void tc06_unknownIngredient_emitsTypedError() {
    stubIngredients(FLTO, GRBF);
    // CHED is intentionally not stubbed -> empty -> unknown

    EmailOrder email = new EmailOrder();
    email.setEmail("craig@habuma.com");
    email.setTacos(Collections.singletonList(taco("Mystery", "FLTO", "CHED")));

    StepVerifier.create(service.convertEmailOrderToDomainOrder(Mono.just(email)))
        .expectErrorSatisfies(error -> {
          org.junit.jupiter.api.Assertions.assertTrue(error instanceof UnknownIngredientException);
          org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("CHED"));
        })
        .verify();
  }

  @Test
  public void tc06_missingUser_emitsTypedError() {
    when(userRepo.findByEmail("ghost@taco.cl")).thenReturn(Mono.empty());

    EmailOrder email = new EmailOrder();
    email.setEmail("ghost@taco.cl");
    email.setTacos(Collections.singletonList(taco("Lonely", "FLTO")));

    StepVerifier.create(service.convertEmailOrderToDomainOrder(Mono.just(email)))
        .expectErrorSatisfies(error -> {
          org.junit.jupiter.api.Assertions.assertTrue(error instanceof EmailOrderConversionException);
          org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("ghost@taco.cl"));
        })
        .verify();
  }

  @Test
  public void tc06_missingPaymentMethod_emitsTypedError() {
    when(paymentMethodRepo.findByUserId("user1")).thenReturn(Mono.empty());

    EmailOrder email = new EmailOrder();
    email.setEmail("craig@habuma.com");
    email.setTacos(Collections.singletonList(taco("Cardless", "FLTO")));

    StepVerifier.create(service.convertEmailOrderToDomainOrder(Mono.just(email)))
        .expectErrorSatisfies(error -> {
          org.junit.jupiter.api.Assertions.assertTrue(error instanceof EmailOrderConversionException);
          org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("no payment"));
        })
        .verify();
  }

  @Test
  public void tc06_noTacos_withValidUser_emitsOrderWithoutTacos() {
    when(userRepo.findByEmail("craig@habuma.com")).thenReturn(Mono.just(HABUMA));
    when(paymentMethodRepo.findByUserId("user1")).thenReturn(Mono.just(CARD));

    EmailOrder email = new EmailOrder();
    email.setEmail("craig@habuma.com");
    email.setTacos(Collections.emptyList());

    StepVerifier.create(service.convertEmailOrderToDomainOrder(Mono.just(email)))
        .assertNext(order ->
            org.junit.jupiter.api.Assertions.assertEquals(0, order.getTacos().size()))
        .verifyComplete();
  }

  private void stubIngredients(Ingredient... ingredients) {
    for (Ingredient ingredient : ingredients) {
      when(ingredientRepo.findById(anyString())).thenReturn(Mono.empty());
    }
    for (Ingredient ingredient : ingredients) {
      when(ingredientRepo.<String>findById(ingredient.getId())).thenReturn(Mono.just(ingredient));
    }
  }

  private static EmailTaco taco(String name, String... ingredientIds) {
    EmailTaco taco = new EmailTaco();
    taco.setName(name);
    taco.setIngredients(Arrays.asList(ingredientIds));
    return taco;
  }

  private static User habuma() {
    User user = new User("habuma", "password", "Craig Walls", "123 North Street",
        "Cross Roads", "TX", "76227", "123-123-1234", "craig@habuma.com");
    user.setId("user1");
    return user;
  }

  private static PaymentMethod card() {
    PaymentMethod method = new PaymentMethod(HABUMA, "tok_abc123", "VISA", "1111", "10/25");
    method.setId("pm-1");
    return method;
  }

}