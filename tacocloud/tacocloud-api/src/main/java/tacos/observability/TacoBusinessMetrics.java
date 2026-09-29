package tacos.observability;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Business counters and timers that explain the shop (TC-32).
 *
 * <p>Actuator used to answer only "UP". These meters answer "what is the shop
 * doing": orders created, failed and cancelled, coupons applied, stock
 * rejections and dead letters, plus how long a placement takes and how long
 * the kitchen needs. The outbox backlog is a gauge the relay keeps honest.
 *
 * <p>Tags stay low-cardinality on purpose ({@code result}, {@code source},
 * {@code transport}...). An {@code orderId}, {@code userId} or correlation
 * id would explode the metrics backend, so it never becomes a tag here; the
 * request id stays in logs and events where it belongs.
 */
@Component
public class TacoBusinessMetrics {

  private final MeterRegistry registry;
  private final AtomicInteger outboxPending = new AtomicInteger();

  public TacoBusinessMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.registry.gauge("tacocloud.outbox.pending", outboxPending);
  }

  public void orderCreated(String source) {
    registry.counter("tacocloud.orders", "result", "created", "source", safe(source)).increment();
  }

  public void orderFailed(String source) {
    registry.counter("tacocloud.orders", "result", "failed", "source", safe(source)).increment();
  }

  public void orderCancelled() {
    registry.counter("tacocloud.orders", "result", "cancelled", "source", "workflow").increment();
  }

  public void couponApplied() {
    registry.counter("tacocloud.coupons", "result", "applied").increment();
  }

  public void stockRejected() {
    registry.counter("tacocloud.inventory", "result", "rejected").increment();
  }

  public void dlqParked(String cause) {
    registry.counter("tacocloud.events.dlq", "cause", safe(cause)).increment();
  }

  public Timer.Sample startPlacement() {
    return Timer.start(registry);
  }

  public void stopPlacement(Timer.Sample sample, String result) {
    sample.stop(registry.timer("tacocloud.order.placement", "result", safe(result)));
  }

  public void recordKitchenLatency(Duration latency) {
    registry.timer("tacocloud.kitchen.latency").record(latency);
  }

  public void setOutboxPending(int pending) {
    outboxPending.set(Math.max(0, pending));
  }

  public int getOutboxPending() {
    return outboxPending.get();
  }

  private static String safe(String value) {
    if (value == null || value.trim().isEmpty()) {
      return "unknown";
    }
    String trimmed = value.trim().toLowerCase();
    return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
  }
}
