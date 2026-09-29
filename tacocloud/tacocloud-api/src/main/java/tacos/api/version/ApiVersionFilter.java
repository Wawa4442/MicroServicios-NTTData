package tacos.api.version;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Versioning without duplicating controllers (TC-35).
 *
 * <p>{@code /api/v1/**} is the canonical contract; {@code /api/**} keeps
 * working but answers with {@code Deprecation: true} and a {@code Link} to
 * its successor, so old callers have a migration path instead of a cliff.
 * The filter rewrites {@code /api/v1/orders} to {@code /api/orders} before
 * the handler lookup, which means the same code serves both prefixes and a
 * new route cannot be added on one prefix and forgotten on the other.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiVersionFilter implements WebFilter {

  static final String V1_PREFIX = "/api/v1/";
  static final String LEGACY_PREFIX = "/api/";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (path.startsWith(V1_PREFIX)) {
      String rewritten = "/api/" + path.substring(V1_PREFIX.length());
      ServerHttpRequest mutated = exchange.getRequest().mutate().path(rewritten).build();
      ServerWebExchange versioned = exchange.mutate().request(mutated).build();
      versioned.getAttributes().put("api.version", "v1");
      versioned.getResponse().getHeaders().set("API-Version", "v1");
      return chain.filter(versioned);
    }
    if (path.startsWith(LEGACY_PREFIX)
        && !path.startsWith("/api/admin/")
        && !path.equals("/api/openapi.yaml")) {
      String successor = "/api/v1/" + path.substring(LEGACY_PREFIX.length());
      exchange.getResponse().getHeaders().set("Deprecation", "true");
      exchange.getResponse().getHeaders().set("Link",
          "<" + successor + ">; rel=\"successor-version\"");
      exchange.getAttributes().put("api.version", "legacy");
    }
    return chain.filter(exchange);
  }
}
