package tacos.search;

import java.util.Locale;

/**
 * A requested sort order: one whitelisted field plus a direction.
 *
 * <p>The field whitelist is the point of this class. A sort parameter that
 * reaches the database unfiltered is a way to ask for sorting by a field the
 * API did not promise, on a collection that may not even have an index for
 * it; a client that typos {@code sort=createdAt,desc;drop()} must get a 400
 * rather than a surprise.
 */
public final class TacoSort {

  private final String field;
  private final boolean descending;

  private TacoSort(String field, boolean descending) {
    this.field = field;
    this.descending = descending;
  }

  /**
   * Parses {@code field}, {@code field,asc} or {@code field,desc}. The
   * field name is matched against {@code sortableFields} case-insensitively
   * and the canonical name is what gets used, so a client cannot smuggle
   * different spellings of the same field into the query.
   */
  public static TacoSort parse(String raw, TacoSearchProperties properties) {
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isEmpty()) {
      return parseDefault(properties);
    }
    String[] parts = trimmed.split(",", 2);
    String requested = parts[0].trim();
    String direction = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";

    for (String allowed : properties.getSortableFields()) {
      if (allowed.equalsIgnoreCase(requested)) {
        return new TacoSort(allowed, isDescending(direction, requested));
      }
    }
    throw new InvalidTacoSearchException("Cannot sort by '" + requested + "'. Allowed fields: "
        + String.join(", ", properties.getSortableFields()) + ".");
  }

  private static TacoSort parseDefault(TacoSearchProperties properties) {
    return parse(properties.getDefaultSort(), properties);
  }

  private static boolean isDescending(String direction, String field) {
    switch (direction) {
      case "asc":
        return false;
      case "desc":
        return true;
      default:
        throw new InvalidTacoSearchException(
            "Unknown sort direction '" + direction + "' for field '" + field
                + "'. Use 'asc' or 'desc'.");
    }
  }

  public String getField() {
    return field;
  }

  public boolean isDescending() {
    return descending;
  }

  @Override
  public String toString() {
    return field + "," + (descending ? "desc" : "asc");
  }

}
