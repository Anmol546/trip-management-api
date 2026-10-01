package com.anmol.trip.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Guard rails for client supplied pagination.
 *
 * <p>An unbounded {@code size} lets one request ask the database for the whole
 * table, so the size is capped rather than trusted.
 */
public final class PageRequests {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    public static Pageable clamp(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }
}
