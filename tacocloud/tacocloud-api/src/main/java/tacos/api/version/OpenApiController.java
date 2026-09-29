package tacos.api.version;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Serves the versioned contract (TC-35).
 *
 * <p>The YAML in {@code src/main/resources/openapi.yaml} is the source of
 * truth reviewed in Git, not a dump of Mongo entities. It lives in both
 * prefixes through the version filter: {@code /api/v1/openapi.yaml} is
 * canonical, {@code /api/openapi.yaml} is the deprecated alias.
 */
@RestController
@RequestMapping(path = "/api/openapi.yaml")
public class OpenApiController {

  @GetMapping(produces = "application/yaml")
  public Mono<String> openapi() {
    return Mono.fromSupplier(() -> {
      try {
        ClassPathResource resource = new ClassPathResource("openapi.yaml");
        byte[] bytes = StreamUtils.copyToByteArray(resource.getInputStream());
        return new String(bytes, StandardCharsets.UTF_8);
      } catch (Exception e) {
        throw new IllegalStateException("OpenAPI document is not readable", e);
      }
    });
  }
}
