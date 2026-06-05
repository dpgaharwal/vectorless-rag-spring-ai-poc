package io.github.dpgaharwal.pageindex.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class TocCheckResult {
    private String tocContent;
    private List<Integer> tocPageList;
    private boolean pageIndexGivenInToc;
}
