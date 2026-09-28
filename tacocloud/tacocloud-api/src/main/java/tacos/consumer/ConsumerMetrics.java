package tacos.consumer;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Small honest counters for the consumer (TC-30).
 *
 * <p>How many events were applied, how many arrived twice, how many
 * retried and how many parked in the DLQ. The operator procedure
 * ("is the kitchen falling behind?") starts here; the business metrics
 * of TC-32 build on top.
 */
@Component
public class ConsumerMetrics {

  private final AtomicLong processed = new AtomicLong();
  private final AtomicLong duplicates = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();
  private final AtomicLong deadLettered = new AtomicLong();

  public void processed() {
    processed.incrementAndGet();
  }

  public void duplicate() {
    duplicates.incrementAndGet();
  }

  public void retried() {
    retries.incrementAndGet();
  }

  public void deadLettered() {
    deadLettered.incrementAndGet();
  }

  public long getProcessed() {
    return processed.get();
  }

  public long getDuplicates() {
    return duplicates.get();
  }

  public long getRetries() {
    return retries.get();
  }

  public long getDeadLettered() {
    return deadLettered.get();
  }
}
