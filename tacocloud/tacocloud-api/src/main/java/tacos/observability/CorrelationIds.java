package tacos.observability;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Small helper for the {@code X-Correlation-Id} header (TC-31).
 *
 * <p>A request can be followed from the API to the kitchen without guessing
 * "the order from around 10:32". The client may send its own id; when it is
 * missing or looks malicious we generate a UUID instead. The id travels in
 * the response header, in the outbox/event payload and in the logs, so the
 * same value ties the three together.
 *
 * <p>Validation is deliberately strict but boring: visible ASCII without
 * newlines, up to 64 chars. Anything with a line break, control char or an
 * absurd length is replaced, which also shuts the door on log injection.
 */
public final class CorrelationIds {

  public static final String HEADER = "X-Correlation-Id";
  public static final String CONTEXT_KEY = "tacocloud.correlationId";
  public static final String MDC_KEY = "correlationId";

  private static final int MAX_LENGTH = 64;
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9\\-_.:]{1,64}");

  private CorrelationIds() {
  }

  public static boolean isValid(String raw) {
    if (raw == null) {
      return false;
    }
    String value = raw.trim();
    if (value.isEmpty() || value.length() > MAX_LENGTH) {
      return false;
    }
    if (value.contains("\n") || value.contains("\r")) {
      return false;
    }
    return SAFE.matcher(value).matches();
  }

  /**
   * Returns the caller's id when it is usable, otherwise a fresh UUID. Never
   * returns null, never returns the order id: one request can produce several
   * events, so the correlation is the request, not the order.
   */
  public static String normalize(String raw) {
    if (isValid(raw)) {
      return raw.trim();
    }
    return UUID.randomUUID().toString();
  }

  public static String generate() {
    return UUID.randomUUID().toString();
  }
}
