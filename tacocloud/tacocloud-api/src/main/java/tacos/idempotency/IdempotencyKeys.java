package tacos.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.TacoLineRequest;

/**
 * Key format and canonical hashing (TC-34).
 *
 * <p>The key is scoped by user, so it never leaks across accounts. The hash
 * covers only the business fields that define "the same purchase": delivery,
 * taco lines (names, sorted ingredient ids, quantities), coupon normalized
 * and payment reference. Money, ids and timestamps are server-owned and play
 * no part: two JSON bodies with different whitespace but the same purchase
 * hash the same.
 */
public final class IdempotencyKeys {

  public static final String HEADER = "Idempotency-Key";

  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9\\-_.:]{8,64}");

  private IdempotencyKeys() {
  }

  public static String normalizeHeader(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    String value = raw.trim();
    if (!KEY.matcher(value).matches()) {
      throw new InvalidIdempotencyKeyException(
          "Idempotency-Key must match [A-Za-z0-9-_.:]{8,64}.");
    }
    return value;
  }

  public static String recordId(String userId, String key) {
    String owner = userId == null ? "anonymous" : userId;
    return owner + ":" + key;
  }

  public static String canonicalHash(OrderCreateRequest request) {
    StringBuilder canonical = new StringBuilder();
    canonical.append(norm(request.getDeliveryName())).append('|');
    canonical.append(norm(request.getDeliveryStreet())).append('|');
    canonical.append(norm(request.getDeliveryCity())).append('|');
    canonical.append(norm(request.getDeliveryState())).append('|');
    canonical.append(norm(request.getDeliveryZip())).append('|');
    List<String> lines = new ArrayList<>();
    if (request.getTacos() != null) {
      for (TacoLineRequest line : request.getTacos()) {
        List<String> ids = line.getIngredientIds() == null
            ? Collections.emptyList()
            : new ArrayList<>(line.getIngredientIds());
        Collections.sort(ids);
        lines.add(norm(line.getName()) + "#" + String.join(",", ids)
            + "x" + line.getQuantity());
      }
    }
    Collections.sort(lines);
    canonical.append(String.join(";", lines)).append('|');
    canonical.append(norm(request.getCouponCode()).toUpperCase()).append('|');
    canonical.append(norm(request.getPaymentMethodId()));
    return sha256(canonical.toString());
  }

  private static String norm(String value) {
    return value == null ? "" : value.trim();
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
