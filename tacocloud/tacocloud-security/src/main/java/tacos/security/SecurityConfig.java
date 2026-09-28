package tacos.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive
                                    .EnableWebFluxSecurity;
import org.springframework.security.config.web.server
                                    .ServerHttpSecurity;
import org.springframework.security.crypto.factory
                                    .PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Reactive, deny-by-default security configuration.
 *
 * <p>The default posture is {@code authenticated()}: every route must be
 * explicitly granted. Roles are USER (the registered customer), KITCHEN (the
 * kitchen gateway) and ADMIN (the operator). The password encoder is a
 * delegating encoder so stored secrets carry the {@code {bcrypt}} prefix and
 * plain-text "encoders" are no longer used anywhere.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http) {
    return http
      .authorizeExchange(exchanges -> exchanges
        // SPA/CORS support: preflight is always allowed.
        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
        // Public pages and static assets.
        .pathMatchers("/", "/index.html", "/favicon.ico",
            "/login", "/register", "/register/**",
            "/assets/**", "/webjars/**",
            "/*.js", "/*.css", "/*.ico", "/*.map",
            "/*.png", "/*.jpg", "/*.svg", "/*.woff2",
            "/actuator/health").permitAll()
        // Kitchen consumes recent orders through a dedicated gateway.
        .pathMatchers("/api/kitchen/**").hasRole("KITCHEN")
        // Operator-only catalog and inventory administration.
        .pathMatchers("/api/admin/**").hasRole("ADMIN")
        // Laboratorio 4: a customer's own favorites and order history. The
        // "me" in the path is the only selector there is, so this rule is what
        // keeps one customer out of another's data.
        .pathMatchers("/api/users/me/**").hasAnyRole("USER", "ADMIN")
        // Order lifecycle belongs to customers, the kitchen and operators;
        // rule-based ownership is enforced in OrderApiService.
        .pathMatchers("/api/orders/**").hasAnyRole("USER", "KITCHEN", "ADMIN")
        // Coupon validation belongs to whoever is building an order.
        .pathMatchers("/api/coupons/**").hasAnyRole("USER", "KITCHEN", "ADMIN")
        // Taco Physics design validation runs before any order is created.
        .pathMatchers(HttpMethod.POST, "/api/tacos/validate")
            .hasAnyRole("USER", "KITCHEN", "ADMIN")
        // A rating is a customer's statement about the catalog, so it is their
        // own write and not catalog administration. It has to be named before
        // the ADMIN-only catch-all below, because authorizeExchange stops at the
        // first matching rule: without it, a customer could not rate.
        .pathMatchers(HttpMethod.PUT, "/api/tacos/*/rating")
            .hasAnyRole("USER", "ADMIN")
        // The catalog can be read by any authenticated principal (the SPA
        // needs it to design tacos) but only administered by operators.
        .pathMatchers(HttpMethod.GET, "/api/ingredients/**")
            .hasAnyRole("USER", "KITCHEN", "ADMIN")
        .pathMatchers(HttpMethod.GET, "/api/tacos/**")
            .hasAnyRole("USER", "KITCHEN")
        .pathMatchers("/api/ingredients/**").hasRole("ADMIN")
        .pathMatchers("/api/tacos/**").hasRole("ADMIN")
        // Legacy Data REST and management endpoints are operator-only.
        .pathMatchers("/data-api/**").hasRole("ADMIN")
        .pathMatchers("/actuator/**").hasRole("ADMIN")
        // Everything else requires an authenticated principal.
        .anyExchange().authenticated())
      .httpBasic()
      .and()
        .formLogin()
      .and()
        .csrf().disable()
      .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    // Delegating encoder: verification supports {bcrypt}, {noop}, ... while
    // new passwords are always hashed as {bcrypt}. NoOpPasswordEncoder is gone.
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

}