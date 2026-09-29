package tacos.api.version;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.yaml.snakeyaml.Yaml;

/**
 * TC-35 contract: the YAML in Git describes what the code serves, without
 * leaking secrets, and a drift would fail here before reaching the demo.
 */
public class OpenApiContractTest {

  private static Map<String, Object> spec() throws Exception {
    ClassPathResource resource = new ClassPathResource("openapi.yaml");
    String yaml = new String(
        StreamUtils.copyToByteArray(resource.getInputStream()), StandardCharsets.UTF_8);
    return new Yaml().load(yaml);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void tc35_specListsImplementedEndpointsWithCorrectShapes() throws Exception {
    Map<String, Object> doc = spec();
    Map<String, Object> paths = (Map<String, Object>) doc.get("paths");

    assertTrue(paths.containsKey("/orders"), "POST /orders must be documented");
    Map<String, Object> orders = (Map<String, Object>) paths.get("/orders");
    Map<String, Object> post = (Map<String, Object>) orders.get("post");
    Map<String, Object> responses = (Map<String, Object>) post.get("responses");
    assertTrue(responses.containsKey("201"), "creating an order is 201, not 200");
    assertTrue(responses.containsKey("409"), "idempotency conflict must be documented");

    assertTrue(paths.containsKey("/orders/{id}/status"), "lifecycle must be documented");
    assertTrue(paths.containsKey("/kitchen/queue"), "kitchen queue must be documented");
    assertTrue(paths.containsKey("/tacos"), "search must be documented");
  }

  @Test
  public void tc35_specMentionsCorrelationAndIdempotencyHeaders() throws Exception {
    String raw = new String(StreamUtils.copyToByteArray(
        new ClassPathResource("openapi.yaml").getInputStream()), StandardCharsets.UTF_8);
    assertTrue(raw.contains("X-Correlation-Id"), "correlation header missing");
    assertTrue(raw.contains("Idempotency-Key"), "idempotency header missing");
    assertTrue(raw.contains("ApiProblem"), "errors must share ApiProblem");
    assertTrue(raw.contains("/api/v1"), "v1 must be the canonical server");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void tc35_specLeaksNoSensitiveFields() throws Exception {
    // Schemas must not define sensitive properties. The prose may say "never
    // a PAN" to document the rule; that sentence is not a leak.
    Map<String, Object> doc = spec();
    Map<String, Object> schemas =
        (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
    String schemasText = new Yaml().dump(schemas).toLowerCase();
    assertFalse(schemasText.contains("password:"), "a schema defines password");
    assertFalse(schemasText.contains("ccnumber"), "a schema defines PAN");
    assertFalse(schemasText.contains("cccvv"), "a schema defines CVV");
    assertFalse(schemasText.contains("authorities"), "a schema defines authorities");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void tc35_incompatibleChangeWouldFail() throws Exception {
    // Guards the shape: OrderCreateRequest must never accept money, and
    // OrderResponse must always carry status+total. If someone adds a total
    // to the request or drops status from the response, this goes red.
    Map<String, Object> doc = spec();
    Map<String, Object> schemas =
        (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
    Map<String, Object> create =
        (Map<String, Object>) schemas.get("OrderCreateRequest");
    Map<String, Object> createProps =
        (Map<String, Object>) create.get("properties");
    assertFalse(createProps.containsKey("total"), "clients must not send total");
    assertFalse(createProps.containsKey("status"), "clients must not send status");

    Map<String, Object> response =
        (Map<String, Object>) schemas.get("OrderResponse");
    Map<String, Object> responseProps =
        (Map<String, Object>) response.get("properties");
    assertTrue(responseProps.containsKey("status"), "response must carry status");
    assertTrue(responseProps.containsKey("total"), "response must carry total");
  }
}
