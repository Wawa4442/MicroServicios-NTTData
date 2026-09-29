package tacos.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.outbox.OutboxRepository;
import tacos.outbox.OutboxStatus;

/**
 * Health that tells whether the outbox is falling behind (TC-32).
 *
 * <p>It counts {@code NEW} rows without blocking: the repository stays
 * reactive and the check stays a publisher the framework subscribes. A small
 * backlog is UP with the number attached; past the threshold it is DOWN so
 * the platform pages someone, with the component named but no credentials
 * attached. The same count feeds the {@code tacocloud.outbox.pending} gauge
 * so metrics and health never disagree.
 */
@Component("outbox")
public class OutboxBacklogHealthIndicator implements ReactiveHealthIndicator {

  private static final int BACKLOG_THRESHOLD = 100;

  private final OutboxRepository repository;
  private final TacoBusinessMetrics metrics;

  public OutboxBacklogHealthIndicator(OutboxRepository repository,
                                      TacoBusinessMetrics metrics) {
    this.repository = repository;
    this.metrics = metrics;
  }

  @Override
  public Mono<Health> health() {
    return repository.findByStatus(OutboxStatus.NEW)
        .count()
        .map(this::toHealth)
        .onErrorResume(e -> Mono.just(Health.down()
            .withDetail("component", "outbox")
            .withDetail("error", "outbox check failed")
            .build()));
  }

  private Health toHealth(long pending) {
    metrics.setOutboxPending((int) Math.min(pending, Integer.MAX_VALUE));
    if (pending > BACKLOG_THRESHOLD) {
      return Health.down()
          .withDetail("component", "outbox")
          .withDetail("pending", pending)
          .withDetail("threshold", BACKLOG_THRESHOLD)
          .build();
    }
    return Health.up()
        .withDetail("component", "outbox")
        .withDetail("pending", pending)
        .build();
  }
}
