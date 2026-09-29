package tacos.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * TC-32 at the unit level: meters move, tags stay low-cardinality.
 */
public class TacoBusinessMetricsTest {

  @Test
  public void tc32_countersAndTimers_moveOnRealScenarios() {
    MeterRegistry registry = new SimpleMeterRegistry();
    TacoBusinessMetrics metrics = new TacoBusinessMetrics(registry);

    metrics.orderCreated("api");
    metrics.orderCreated("api");
    metrics.orderFailed("api");
    metrics.orderCancelled();
    metrics.couponApplied();
    metrics.stockRejected();
    metrics.dlqParked("permanent_error");
    metrics.recordKitchenLatency(Duration.ofMinutes(7));
    metrics.setOutboxPending(3);

    assertEquals(2.0, registry.counter("tacocloud.orders", "result", "created", "source", "api").count());
    assertEquals(1.0, registry.counter("tacocloud.orders", "result", "failed", "source", "api").count());
    assertEquals(1.0, registry.counter("tacocloud.orders", "result", "cancelled", "source", "workflow").count());
    assertEquals(1.0, registry.counter("tacocloud.coupons", "result", "applied").count());
    assertEquals(1.0, registry.counter("tacocloud.inventory", "result", "rejected").count());
    assertEquals(1.0, registry.counter("tacocloud.events.dlq", "cause", "permanent_error").count());
    assertEquals(1, registry.timer("tacocloud.kitchen.latency").count());
    assertEquals(3, metrics.getOutboxPending());
  }

  @Test
  public void tc32_placementTimer_recordsByResult() {
    MeterRegistry registry = new SimpleMeterRegistry();
    TacoBusinessMetrics metrics = new TacoBusinessMetrics(registry);

    metrics.stopPlacement(metrics.startPlacement(), "created");

    assertEquals(1, registry.timer("tacocloud.order.placement", "result", "created").count());
  }

  @Test
  public void tc32_noHighCardinalityTagsOrSensitiveData() {
    MeterRegistry registry = new SimpleMeterRegistry();
    TacoBusinessMetrics metrics = new TacoBusinessMetrics(registry);
    metrics.orderCreated("api");
    metrics.dlqParked("retry_exhausted");

    registry.forEachMeter(meter -> meter.getId().getTags().forEach(tag -> {
      String key = tag.getKey().toLowerCase();
      String value = tag.getValue().toLowerCase();
      assertTrue(!key.contains("orderid") && !key.contains("userid")
          && !key.contains("correlation"), "tag key leaks identity: " + key);
      // Values are fixed vocabularies (created/failed/api...), never ids.
      assertTrue(value.length() <= 32, "tag value too long for a low-cardinality tag: " + value);
    }));
  }
}
