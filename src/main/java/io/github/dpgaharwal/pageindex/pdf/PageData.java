package io.github.dpgaharwal.pageindex.pdf;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Content and metadata for a single PDF page.
 * pageNumber is 1-based (first page = 1).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageData {
    /** 1-based page number */
    private int pageNumber;
    private String text;
    private int tokenCount;
}
