package com.anmol.trip.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope for {@code GET /api/v1/trips}.
 *
 * <p>A hand rolled page envelope rather than serialising Spring's {@code Page}
 * directly, because the default JSON representation of {@code Page} is unstable
 * across Spring versions and leaks implementation detail.
 */
@Schema(name = "PageResponse", description = "A single page of results")
public record PageResponse<T>(

        @Schema(description = "Items on this page") List<T> content,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "137") long totalElements,
        @Schema(example = "7") int totalPages,
        @Schema(example = "true") boolean first,
        @Schema(example = "false") boolean last,
        @Schema(example = "true") boolean empty
) {

    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.isEmpty());
    }
}
