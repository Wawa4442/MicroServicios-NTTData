package tacos.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * TC-31 through the filter: header echo, Reactor context and MDC hygiene.
 */
public class CorrelationWebFilterTest {

  private final CorrelationWebFilter filter = new CorrelationWebFilter();

  @Test
  public void tc31_requestWithoutHeader_receivesUuidInResponse() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/orders").build());

    WebFilterChain chain = e -> {
      String inExchange = (String) e.getAttributes().get(CorrelationIds.CONTEXT_KEY);
      return Mono.subscriberContext()
          .doOnNext(ctx -> {
            String ctxId = ctx.getOrDefault(CorrelationIds.CONTEXT_KEY, "missing");
            // The Reactor context must carry the same id the exchange saw.
            assertEquals(inExchange, ctxId);
          })
          .then();
    };

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    String echoed = exchange.getResponse().getHeaders().getFirst(CorrelationIds.HEADER);
    assertTrue(CorrelationIds.isValid(echoed));
    // The request thread must not leak into the next request.
    assertNull(MDC.get(CorrelationIds.MDC_KEY));
  }

  @Test
  public void tc31_validHeader_isKeptAndEchoed() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/orders")
            .header(CorrelationIds.HEADER, "demo-123")
            .build());

    WebFilterChain chain = e -> Mono.empty();

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertEquals("demo-123",
        exchange.getResponse().getHeaders().getFirst(CorrelationIds.HEADER));
    assertEquals("demo-123",
        exchange.getAttributes().get(CorrelationIds.CONTEXT_KEY));
    assertNull(MDC.get(CorrelationIds.MDC_KEY));
  }

  @Test
  public void tc31_maliciousHeader_isReplacedBeforeDownstream() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/orders")
            .header(CorrelationIds.HEADER, "bad\ninjection")
            .build());

    WebFilterChain chain = e -> {
      String kept = (String) e.getAttributes().get(CorrelationIds.CONTEXT_KEY);
      assertTrue(CorrelationIds.isValid(kept));
      return Mono.empty();
    };

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    String echoed = exchange.getResponse().getHeaders().getFirst(CorrelationIds.HEADER);
    assertTrue(CorrelationIds.isValid(echoed));
    assertNull(MDC.get(CorrelationIds.MDC_KEY));
  }
}
