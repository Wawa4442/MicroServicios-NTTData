package tacos.search;

import java.util.Locale;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;
import tacos.paging.PageBounds;

/**
 * A validated catalog search request (TC-19). Immutable, and constructed only
 * through {@link #of}, so by the time a query reaches the database adapter it
 * is known to be in range, whitelisted and enum-typed.
 *
 * <p>Every filter is optional and absent means "no filter", so the empty query
 * is a valid query: it returns the first page of the catalog ordered by
 * creation date.
 *
 * <p>The free-text term is kept raw here on purpose. Turning it into a pattern
 * belongs to the adapter that owns MongoDB's escaping rules, and keeping the
 * untrusted string inert until then is what makes the "no client-shaped
 * regex" guarantee easy to read and to test.
 */
public final class TacoSearchQuery {

  private final String name;
  private final String ingredientId;
  private final DietaryTag diet;
  private final Allergen excludeAllergen;
  private final SpiceLevel spice;
  private final TacoSort sort;
  private final PageBounds page;

  private TacoSearchQuery(String name, String ingredientId, DietaryTag diet,
      Allergen excludeAllergen, SpiceLevel spice, TacoSort sort, PageBounds page) {
    this.name = name;
    this.ingredientId = ingredientId;
    this.diet = diet;
    this.excludeAllergen = excludeAllergen;
    this.spice = spice;
    this.sort = sort;
    this.page = page;
  }

  public static TacoSearchQuery of(String name, String ingredientId, String diet,
      String excludeAllergen, String spice, Integer pageNumber, Integer pageSize,
      String sort, TacoSearchProperties properties) {
    return new TacoSearchQuery(
        text(name, properties),
        identifier(ingredientId, "ingredientId"),
        enumValue(diet, DietaryTag.class, "diet"),
        enumValue(excludeAllergen, Allergen.class, "excludeAllergen"),
        enumValue(spice, SpiceLevel.class, "spice"),
        TacoSort.parse(sort, properties),
        PageBounds.of(pageNumber, pageSize, properties.getDefaultSize(),
                properties.getMaxSize())
            .cappedAt(properties.getMaxOffset()));
  }

  /** Convenience factory for an unfiltered, default-sorted first page. */
  public static TacoSearchQuery firstPage(TacoSearchProperties properties) {
    return of(null, null, null, null, null, 0, null, null, properties);
  }

  private static String text(String raw, TacoSearchProperties properties) {
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > properties.getMaxTextLength()) {
      throw new InvalidTacoSearchException("name must be at most "
          + properties.getMaxTextLength() + " characters long.");
    }
    return trimmed;
  }

  private static String identifier(String raw, String field) {
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > 40) {
      throw new InvalidTacoSearchException(field + " is not a valid identifier.");
    }
    return trimmed;
  }

  private static <E extends Enum<E>> E enumValue(String raw, Class<E> type, String field) {
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    try {
      return Enum.valueOf(type, trimmed.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      StringBuilder allowed = new StringBuilder();
      for (E constant : type.getEnumConstants()) {
        if (allowed.length() > 0) {
          allowed.append(", ");
        }
        allowed.append(constant.name());
      }
      throw new InvalidTacoSearchException(
          "'" + trimmed + "' is not a valid " + field + ". Allowed values: " + allowed + ".");
    }
  }

  /**
   * The request as a {@link Pageable}, sorted with the stable trailing key
   * appended so that paging cannot skip or repeat a row.
   */
  public Pageable pageable() {
    Sort.Order stable = new Sort.Order(
        sort.isDescending() ? Sort.Direction.DESC : Sort.Direction.ASC, "_id");
    return page.toPageable(Sort.by(
        new Sort.Order(sort.isDescending() ? Sort.Direction.DESC : Sort.Direction.ASC,
            sort.getField()),
        stable));
  }

  public String getName() {
    return name;
  }

  public String getIngredientId() {
    return ingredientId;
  }

  public DietaryTag getDiet() {
    return diet;
  }

  public Allergen getExcludeAllergen() {
    return excludeAllergen;
  }

  public SpiceLevel getSpice() {
    return spice;
  }

  public TacoSort getSort() {
    return sort;
  }

  public PageBounds getPage() {
    return page;
  }

  public boolean isUnfiltered() {
    return name == null && ingredientId == null && diet == null
        && excludeAllergen == null && spice == null;
  }

}
