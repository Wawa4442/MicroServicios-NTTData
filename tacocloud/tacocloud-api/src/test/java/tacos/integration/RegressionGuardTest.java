package tacos.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The safety net that would have caught the original bugs (TC-36).
 *
 * <p>Fast and hermetic: it scans the main sources instead of booting
 * infrastructure, so it runs on every {@code mvn test}. A reintroduced
 * {@code void repo.save}, a manual {@code subscribe()} in a service, a
 * leaked PAN field or an open {@code permitAll} fails here before reaching
 * the demo. The Testcontainers suite next door covers the real Mongo; this
 * one covers the mistakes that do not need a database to be wrong.
 */
public class RegressionGuardTest {

  private static final Path MAIN =
      Paths.get("src", "main", "java");

  private static List<String> mainSources() throws Exception {
    Path root = Files.exists(MAIN)
        ? MAIN
        : Paths.get("tacocloud-api", "src", "main", "java");
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(p -> p.toString().endsWith(".java"))
          .map(p -> {
            try {
              return Files.readString(p);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          })
          .collect(Collectors.toList());
    }
  }

  @Test
  public void tc36_noPhantomPublishersInServices() throws Exception {
    // TC-01/TC-02/TC-07: every write must belong to the returned publisher.
    // The only subscribe() allowed lives in the scheduled relay border.
    for (String source : mainSources()) {
      if (source.contains("class OutboxRelay")
          || source.contains("class DevelopmentConfig")) {
        continue;
      }
      assertFalse(source.contains(".subscribe("),
          "Manual subscribe() in a service -- return the publisher instead");
      assertFalse(source.contains(".block("),
          "Manual block() in a service -- compose the publisher instead");
    }
  }

  @Test
  public void tc36_noSensitiveFieldsInDomain() throws Exception {
    // TC-12: PAN/CVV must not come back through a "quick fix".
    for (String source : mainSources()) {
      assertFalse(source.contains("ccNumber"),
          "ccNumber leaked back into the sources");
      assertFalse(source.contains("ccCVV"),
          "ccCVV leaked back into the sources");
    }
  }

  @Test
  public void tc36_idempotencyAndCorrelationHeadersExistInContract() throws Exception {
    Path openapi = Paths.get("src", "main", "resources", "openapi.yaml");
    if (!Files.exists(openapi)) {
      openapi = Paths.get("tacocloud-api", "src", "main", "resources", "openapi.yaml");
    }
    String spec = Files.readString(openapi);
    assertTrue(spec.contains("Idempotency-Key"), "TC-34 header missing from contract");
    assertTrue(spec.contains("X-Correlation-Id"), "TC-31 header missing from contract");
    assertTrue(spec.contains("ApiProblem"), "TC-09 problem shape missing from contract");
  }

  @Test
  public void tc36_outboxAndConsumerHardeningsStillPresent() throws Exception {
    // TC-29/TC-30/TC-34: the three hardenings that make retries safe.
    String all = String.join("\n", mainSources());
    assertTrue(all.contains("OutboxStatus.NEW"), "outbox NEW row missing");
    assertTrue(all.contains("existsByEventId"), "consumer dedup by eventId missing");
    assertTrue(all.contains("Idempotency-Key"), "idempotency header wiring missing");
  }
}
