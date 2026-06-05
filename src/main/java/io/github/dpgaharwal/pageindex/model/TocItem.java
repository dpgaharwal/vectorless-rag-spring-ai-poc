package io.github.dpgaharwal.pageindex.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in a Table of Contents list, as produced by the LLM TOC extraction calls.
 * Mirrors the Python TocItem dict with the same JSON keys for wire compatibility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TocItem {
    /** Dot-notation hierarchy, e.g. "1", "1.2", "1.2.3" */
    private String structure;

    private String title;

    /** TOC-reported page number (before offset calculation) */
    private Integer page;

    /** Physical (0-based index into the page list) assigned after extraction */
    @JsonProperty("physical_index")
    private Integer physicalIndex;

    /**
     * Whether the section title appears at the very start of its page.
     * "yes" means the title starts the page content; "no" means it appears mid-page.
     */
    @JsonProperty("appear_start")
    private String appearStart;
}
