package tacos.coupon;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Public coupon verification (TC-15). It never reveals which codes exist: it
 * only answers about the code the client submitted, and a rejected code is a
 * 200 decision, not a 4xx that would let a bot probe for promotions.
 */
@RestController
@RequestMapping(path = "/api/coupons", produces = "application/json")
public class CouponController {

  private final CouponEngine engine;

  public CouponController(CouponEngine engine) {
    this.engine = engine;
  }

  @PostMapping(path = "/validate", consumes = "application/json")
  public Mono<CouponValidateResponse> validate(
      @RequestBody @Valid CouponValidateRequest request) {
    return Mono.fromSupplier(() -> {
      String normalized = request.getCode().trim().toUpperCase();
      return CouponValidateResponse.from(normalized,
          engine.evaluate(request.getSubtotal(), request.getCode()));
    });
  }

}