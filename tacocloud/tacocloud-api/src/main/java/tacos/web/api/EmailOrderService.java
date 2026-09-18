package tacos.web.api;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.pricing.PricingService;
import tacos.web.api.EmailOrder.EmailTaco;

@Service
public class EmailOrderService {

  private final UserRepository userRepo;
  private final IngredientRepository ingredientRepo;
  private final PaymentMethodRepository paymentMethodRepo;
  private final PricingService pricing;

  public EmailOrderService(UserRepository userRepo, IngredientRepository ingredientRepo,
      PaymentMethodRepository paymentMethodRepo, PricingService pricing) {
    this.userRepo = userRepo;
    this.ingredientRepo = ingredientRepo;
    this.paymentMethodRepo = paymentMethodRepo;
    this.pricing = pricing;
  }

  /**
   * Turns an {@link EmailOrder} into a complete {@link TacoOrder} in a single
   * reactive chain. The user and the payment method must exist, and every
   * ingredient id must resolve, otherwise a typed error is emitted and no
   * order is produced.
   *
   * <p>Tacos are resolved sequentially with {@code concatMap}, preserving the
   * order they appear in the email; a taco is only built once all of its
   * ingredients have been fetched.
   */
  public Mono<TacoOrder> convertEmailOrderToDomainOrder(Mono<EmailOrder> emailOrder) {
    return emailOrder
        .flatMap(email -> resolveUser(email)
            .flatMap(user -> paymentMethodFor(user)
                .flatMap(payment -> buildOrder(user, payment, email))));
  }

  private Mono<User> resolveUser(EmailOrder email) {
    return userRepo.findByEmail(email.getEmail())
        .switchIfEmpty(Mono.error(new EmailOrderConversionException(
            "No user registered for email: " + email.getEmail())));
  }

  private Mono<PaymentMethod> paymentMethodFor(User user) {
    return paymentMethodRepo.findByUserId(user.getId())
        .switchIfEmpty(Mono.error(new EmailOrderConversionException(
            "User has no payment method on file: " + user.getUsername())));
  }

  private Mono<TacoOrder> buildOrder(User user, PaymentMethod payment, EmailOrder email) {
    return Flux.fromIterable(tacosOf(email))
        .concatMap(this::toTaco)
        .collectList()
        .map(tacos -> {
          TacoOrder order = new TacoOrder();
          order.setUser(user);
          order.setPaymentMethodId(payment.getId());
          order.setDeliveryName(user.getFullname());
          order.setDeliveryStreet(user.getStreet());
          order.setDeliveryCity(user.getCity());
          order.setDeliveryState(user.getState());
          order.setDeliveryZip(user.getZip());
          order.setPlacedAt(new Date());
          tacos.forEach(order::addTaco);
          BigDecimal subtotal = pricing.subtotalOf(order.getTacos());
          order.setCurrency(pricing.currency());
          order.setSubtotal(subtotal);
          order.setDiscount(pricing.zero());
          order.setTotal(subtotal);
          return order;
        });
  }

  private Mono<Taco> toTaco(EmailTaco emailTaco) {
    return Flux.fromIterable(ingredientsOf(emailTaco))
        .concatMap(id -> ingredientRepo.findById(id)
            .switchIfEmpty(Mono.error(new UnknownIngredientException(id))))
        .collectList()
        .map(ingredients -> {
          Taco taco = new Taco();
          taco.setName(emailTaco.getName());
          taco.setIngredients(ingredients);
          return pricing.priceLine(taco, 1);
        });
  }

  private static List<EmailTaco> tacosOf(EmailOrder email) {
    return email.getTacos() == null
        ? Collections.emptyList() : email.getTacos();
  }

  private static List<String> ingredientsOf(EmailTaco emailTaco) {
    return emailTaco.getIngredients() == null
        ? Collections.emptyList() : emailTaco.getIngredients();
  }

}