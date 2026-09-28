package tacos.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tacos.messaging.OrderMessagingService;

/**
 * Background delivery of outbox rows (TC-29).
 *
 * <p>A scheduler border, and therefore the one place besides the framework
 * allowed to subscribe: it claims a batch, sends each event, and marks the
 * outcome. A broker outage keeps rows re-triable with backoff; a restart
 * re-reads them, so nothing confirmed is ever lost. Delivery is at least
 * once; duplicates are the consumer's job (TC-30).
 */
@Component
@ConditionalOnProperty(name = "tacocloud.outbox.enabled",
    havingValue = "true", matchIfMissing = true)
@Slf4j
public class OutboxRelay {

  private final OutboxService outbox;
  private final OrderMessagingService messages;
  private final OutboxProperties props;

  public OutboxRelay(OutboxService outbox, OrderMessagingService messages,
                     OutboxProperties props) {
    this.outbox = outbox;
    this.messages = messages;
    this.props = props;
  }

  @Scheduled(fixedDelayString = "${tacocloud.outbox.poll-interval:5s}")
  public void drain() {
    if (!props.isEnabled()) {
      return;
    }
    outbox.claimBatch(props.getBatchSize())
        .concatMap(row -> messages.sendEvent(outbox.readPayload(row))
            .then(outbox.markPublished(row).then())
            .onErrorResume(e -> {
              log.warn("Outbox publish failed for event {}: {}",
                  row.getEventId(), e.toString());
              return outbox.markFailed(row, e.toString()).then();
            }))
        .subscribe(
            ignored -> { },
            e -> log.warn("Outbox drain failed: {}", e.toString()));
  }
}
