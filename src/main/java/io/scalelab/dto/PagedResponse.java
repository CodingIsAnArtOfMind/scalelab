package io.scalelab.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Phase 3 — Generic paginated response wrapper.
 * Contains the page data plus pagination metadata.
 */
@Data
public class PagedResponse<T> implements Serializable {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;

    public PagedResponse() {}

    public PagedResponse(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.last = last;
    }
}

