package tacos.api.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * TC-35 versioning: v1 canonical, legacy deprecated, same handlers.
 */
public class ApiVersionFilterTest {

  private final ApiVersionFilter filter = new ApiVersionFilter();

  @Test
  public void tc35_v1IsRewrittenToLegacyHandler() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/orders").build());

    StepVerifier.create(filter.filter(exchange, e -> {
      assertEquals("/api/orders", e.getRequest().getPath().value());
      assertEquals("v1", e.getAttributes().get("api.version"));
      assertEquals("v1", e.getResponse().getHeaders().getFirst("API-Version"));
      return Mono.empty();
    })).verifyComplete();
  }

  @Test
  public void tc35_legacyKeepsWorkingButAdvertisesSuccessor() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/orders").build());

    StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

    assertEquals("true", exchange.getResponse().getHeaders().getFirst("Deprecation"));
    String link = exchange.getResponse().getHeaders().getFirst("Link");
    assertTrue(link != null && link.contains("/api/v1/orders"));
    assertEquals("legacy", exchange.getAttributes().get("api.version"));
  }

  @Test
  public void tc35_adminAndOpenapiAreNotMarkedDeprecated() {
    MockServerWebExchange admin = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/admin/announcements").build());
    StepVerifier.create(filter.filter(admin, e -> Mono.empty())).verifyComplete();
    assertTrue(admin.getResponse().getHeaders().getFirst("Deprecation") == null);
  }
}
