package tacos.web.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.consumer.DeadLetter;
import tacos.consumer.DeadLetterRepository;
import tacos.consumer.OrderEventConsumer;
import tacos.messaging.OrderEvent;

/**
 * Operator view of poison messages (TC-30), under {@code /api/admin/**}
 * which the security configuration restricts to ADMIN.
 *
 * <p>Listing shows what parked and why; replay re-drives one letter after
 * the cause was fixed. The event itself is re-loaded from the outbox
 * payload, never rebuilt by hand, so a replay cannot invent a new meaning.
 */
@RestController
@RequestMapping(path = "/api/admin/dlq", produces = "application/json")
public class AdminDlqController {

  private final DeadLetterRepository letters;
  private final OrderEventConsumer consumer;
  private final tacos.outbox.OutboxRepository outbox;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  public AdminDlqController(DeadLetterRepository letters,
                            OrderEventConsumer consumer,
                            tacos.outbox.OutboxRepository outbox,
                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
    this.letters = letters;
    this.consumer = consumer;
    this.outbox = outbox;
    this.mapper = mapper;
  }

  @GetMapping
  public Flux<DeadLetter> letters() {
    return letters.findAllByOrderByFailedAtDesc();
  }

  @PostMapping("/{eventId}/replay")
  public Mono<Void> replay(@PathVariable("eventId") String eventId) {
    return consumer.replay(eventId, id ->
        outbox.findByEventId(id)
            .switchIfEmpty(Mono.error(new tacos.workflow.InvalidOrderStatusException(
                "No outbox payload for event " + id)))
            .flatMap(row -> {
              try {
                return Mono.just(mapper.readValue(row.getPayload(), OrderEvent.class));
              } catch (Exception e) {
                return Mono.error(new tacos.workflow.InvalidOrderStatusException(
                    "Outbox payload for event " + id + " is not readable."));
              }
            }));
  }
}
