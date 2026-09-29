package tacos.observability;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * HTTP edge for correlation (TC-31).
 *
 * <p>Reads {@code X-Correlation-Id}, keeps it when it is safe and mints one
 * when it is not, then echoes it back so the caller can quote it in a ticket.
 * The value is exposed three ways: as a response header, as an exchange
 * attribute for controllers that still read it manually, and as Reactor
 * {@link Context} for async chains.
 *
 * <p>MDC is a thread-local and Reactor hops threads, so the filter does not
 * pretend MDC propagates by itself: it sets the key for the request thread,
 * logs without sensitive bodies, and clears it in {@code doFinally} so one
 * request never stains the next. Downstream reactive code should read the id
 * from the Reactor context (or the exchange attribute), not by assuming the
 * MDC followed it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationWebFilter implements WebFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String raw = exchange.getRequest().getHeaders().getFirst(CorrelationIds.HEADER);
    String correlationId = CorrelationIds.normalize(raw);

    exchange.getAttributes().put(CorrelationIds.CONTEXT_KEY, correlationId);
    exchange.getResponse().getHeaders().set(CorrelationIds.HEADER, correlationId);

    MDC.put(CorrelationIds.MDC_KEY, correlationId);
    return chain.filter(exchange)
        .subscriberContext(Context.of(CorrelationIds.CONTEXT_KEY, correlationId))
        .doFinally(signal -> MDC.remove(CorrelationIds.MDC_KEY));
  }
}
