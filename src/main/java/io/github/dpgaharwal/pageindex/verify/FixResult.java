package io.github.dpgaharwal.pageindex.verify;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FixResult {
    private int listIndex;
    private String title;
    private Integer physicalIndex;
    private boolean isValid;
}
