package tacos.observability;

import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tacos.outbox.OutboxRepository;
import tacos.outbox.OutboxStatus;

/**
 * TC-32 health: backlog visible, degraded explained, no blocking.
 */
public class OutboxBacklogHealthIndicatorTest {

  @Test
  public void tc32_smallBacklog_isUpWithCount() {
    OutboxRepository repo = Mockito.mock(OutboxRepository.class);
    when(repo.findByStatus(OutboxStatus.NEW)).thenReturn(Flux.empty());
    OutboxBacklogHealthIndicator health =
        new OutboxBacklogHealthIndicator(repo, new TacoBusinessMetrics(new SimpleMeterRegistry()));

    StepVerifier.create(health.health())
        .expectNextMatches(h -> h.getStatus().equals(Status.UP)
            && h.getDetails().get("pending").equals(0L))
        .verifyComplete();
  }

  @Test
  public void tc32_hugeBacklog_isDownWithExplanation() {
    OutboxRepository repo = Mockito.mock(OutboxRepository.class);
    when(repo.findByStatus(OutboxStatus.NEW))
        .thenReturn(Flux.range(0, 150).map(i -> new tacos.outbox.OutboxEvent()));
    OutboxBacklogHealthIndicator health =
        new OutboxBacklogHealthIndicator(repo, new TacoBusinessMetrics(new SimpleMeterRegistry()));

    StepVerifier.create(health.health())
        .expectNextMatches(h -> h.getStatus().equals(Status.DOWN)
            && h.getDetails().get("component").equals("outbox"))
        .verifyComplete();
  }

  @Test
  public void tc32_repositoryFailure_isDownWithoutSecrets() {
    OutboxRepository repo = Mockito.mock(OutboxRepository.class);
    when(repo.findByStatus(OutboxStatus.NEW))
        .thenReturn(Flux.error(new RuntimeException("mongodb://user:secret@host")));
    OutboxBacklogHealthIndicator health =
        new OutboxBacklogHealthIndicator(repo, new TacoBusinessMetrics(new SimpleMeterRegistry()));

    StepVerifier.create(health.health())
        .expectNextMatches(h -> h.getStatus().equals(Status.DOWN)
            && !h.getDetails().toString().contains("secret"))
        .verifyComplete();
  }
}
