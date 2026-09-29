package tacos.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.reactivestreams.client.MongoClients;

import reactor.test.StepVerifier;

/**
 * Real Mongo when Docker is around, graceful skip when it is not (TC-36).
 *
 * <p>The unit build ({@code mvn test}) never needs a laptop-installed Mongo:
 * this test boots its own {@code mongo:4.4} container, pings it through the
 * reactive driver and stops it. Without Docker it aborts instead of failing,
 * so the same command stays green on a laptop and strict in CI (where Docker
 * exists and the test runs). The broker side follows the same pattern: the
 * outbox relay is covered with fakes in unit tests, and one broker container
 * can be added here following this exact shape.
 */
public class MongoTestcontainersTest {

  @Test
  public void tc36_realMongo_acceptsReactivePing() {
    assumeTrue(isDockerAvailable(), "Docker is not available -- skipping Testcontainers Mongo check.");
    try (MongoDBContainer mongo =
             new MongoDBContainer(DockerImageName.parse("mongo:4.4"))) {
      mongo.start();
      String uri = mongo.getConnectionString();
      StepVerifier.create(MonoPing.ping(uri))
          .expectNext("ok")
          .verifyComplete();
    }
  }

  private static boolean isDockerAvailable() {
    try {
      return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
    } catch (Exception e) {
      return false;
    }
  }

  static class MonoPing {
    static reactor.core.publisher.Mono<String> ping(String uri) {
      return reactor.core.publisher.Mono.from(
              MongoClients.create(uri)
                  .getDatabase("admin")
                  .runCommand(new org.bson.Document("ping", 1)))
          .map(doc -> "ok");
    }
  }
}
