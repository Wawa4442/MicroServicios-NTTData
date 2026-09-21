package tacos.coupon;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * TC-15 wire contract: {@code POST /api/coupons/validate} answers a decision
 * (always HTTP 200) about the code the client sent, and never enumerates the
 * catalog.
 */
public class CouponControllerTest {

  private WebTestClient client;

  @BeforeEach
  public void setup() {
    CouponProperties properties = new CouponProperties();
    CouponDefinition coupon = new CouponDefinition();
    coupon.setCode("WELCOME10");
    coupon.setType(CouponType.PERCENTAGE);
    coupon.setValue(new BigDecimal("10"));
    coupon.setMinSubtotal(new BigDecimal("10.00"));
    coupon.setMaxDiscount(new BigDecimal("5.00"));
    coupon.setActiveFrom(LocalDate.of(2000, 1, 1));
    coupon.setActiveTo(LocalDate.of(2099, 12, 31));
    properties.getCoupons().add(coupon);

    Clock clock = Clock.fixed(
        Instant.parse("2025-06-15T12:00:00Z"), ZoneOffset.UTC);
    client = WebTestClient.bindToController(new CouponController(
        new CouponEngine(properties, clock))).build();
  }

  @Test
  public void validCoupon_isA200Application_withNormalizedCode() {
    client.post().uri("/api/coupons/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"code\":\"  welcome10 \",\"subtotal\":100.00}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.code").isEqualTo("WELCOME10")
        .jsonPath("$.status").isEqualTo("APPLIED")
        .jsonPath("$.valid").isEqualTo(true)
        .jsonPath("$.discount").isEqualTo(5.00);
  }

  @Test
  public void unknownCoupon_isA200Decision_notAnError() {
    client.post().uri("/api/coupons/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"code\":\"NOEXISTE\",\"subtotal\":100.00}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UNKNOWN_CODE")
        .jsonPath("$.valid").isEqualTo(false)
        .jsonPath("$.discount").isEqualTo(0);
  }

  @Test
  public void minimumNotMet_isA200Decision() {
    client.post().uri("/api/coupons/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"code\":\"WELCOME10\",\"subtotal\":4.00}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("MINIMUM_NOT_MET")
        .jsonPath("$.valid").isEqualTo(false);
  }

  @Test
  public void blankCode_isA400ValidationError() {
    client.post().uri("/api/coupons/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"code\":\"\",\"subtotal\":100.00}")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  public void negativeSubtotal_isA400ValidationError() {
    client.post().uri("/api/coupons/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"code\":\"WELCOME10\",\"subtotal\":-1.00}")
        .exchange()
        .expectStatus().isBadRequest();
  }

}