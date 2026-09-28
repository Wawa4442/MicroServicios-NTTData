package tacos.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A validated page window, shared by every endpoint that pages (TC-19 search,
 * TC-21 favorites, TC-23 order history and its admin counterpart).
 *
 * <p>Having one place that decides what a legal page is removes three classes
 * of bug: a client asking for {@code size=1000000} and dragging the database
 * down, a negative page silently becoming the last page, and a
 * default-dependent behavior that changes when somebody edits a controller.
 *
 * <p>Out-of-range values are <b>rejected</b>, not clamped. Clamping a
 * {@code size} of 10&nbsp;000 down to 100 is convenient but silently returns
 * something the client did not ask for; a 400 tells it exactly what is wrong.
 */
public final class PageBounds {

  private final int page;
  private final int size;

  private PageBounds(int page, int size) {
    this.page = page;
    this.size = size;
  }

  /**
   * @param page         zero-based page index, {@code null} means "first page"
   * @param size         page size, {@code null} means {@code defaultSize}
   * @param defaultSize  size used when the client does not ask for one
   * @param maxSize      hard ceiling on the page size
   */
  public static PageBounds of(Integer page, Integer size, int defaultSize, int maxSize) {
    int effectiveDefault = clampSize(defaultSize, maxSize);
    int effectiveSize = size == null ? effectiveDefault : size;
    if (effectiveSize < 1) {
      throw new InvalidPageBoundsException("size must be at least 1.");
    }
    if (effectiveSize > maxSize) {
      throw new InvalidPageBoundsException(
          "size must not exceed " + maxSize + ".");
    }
    int effectivePage = page == null ? 0 : page;
    if (effectivePage < 0) {
      throw new InvalidPageBoundsException("page must not be negative.");
    }
    return new PageBounds(effectivePage, effectiveSize);
  }

  /**
   * Multiplies page and size to reject the classic deep-paging attack, where
   * the database is asked to skip two million documents before returning ten.
   */
  public PageBounds cappedAt(int maxOffset) {
    if ((long) page * size > maxOffset) {
      throw new InvalidPageBoundsException(
          "page " + page + " with size " + size + " exceeds the maximum reachable offset of "
              + maxOffset + "; narrow your filters instead of paging deeper.");
    }
    return this;
  }

  public Pageable toPageable(Sort sort) {
    return PageRequest.of(page, size, sort);
  }

  public int getPage() {
    return page;
  }

  public int getSize() {
    return size;
  }

  private static int clampSize(int defaultSize, int maxSize) {
    if (defaultSize < 1) {
      return Math.min(1, maxSize);
    }
    return Math.min(defaultSize, maxSize);
  }

}
