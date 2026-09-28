package tacos.api.dto;

import java.util.List;

import lombok.Value;

/**
 * Minimal pagination envelope (TC-19). It carries the smallest set of facts a
 * client needs to render a pager — the window it asked for, the total and
 * whether another page exists — and nothing else. Embedding whole entities
 * here would leak persistence details, and returning the full
 * {@code Page} machinery would tie the contract to Spring Data.
 */
@Value
public class PageResponse<T> {

  private final List<T> content;
  private final int page;
  private final int size;
  private final long totalElements;
  private final int totalPages;
  private final boolean hasNext;

  public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
    int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
    return new PageResponse<>(content, page, size, total, totalPages, page + 1 < totalPages);
  }

}
