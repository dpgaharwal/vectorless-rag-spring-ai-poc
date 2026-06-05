package io.github.dpgaharwal.pageindex.toc.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/** A matched pair of TOC-reported page number and physical page index. Used for offset calculation. */
@Data
@AllArgsConstructor
public class PagePair {
    private int tocPage;
    private int physicalIndex;

    public int difference() {
        return physicalIndex - tocPage;
    }
}
