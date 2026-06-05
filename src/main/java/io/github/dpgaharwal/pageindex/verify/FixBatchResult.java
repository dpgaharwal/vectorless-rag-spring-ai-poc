package io.github.dpgaharwal.pageindex.verify;

import io.github.dpgaharwal.pageindex.model.TocItem;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class FixBatchResult {
    private List<TocItem> updatedItems;
    private List<TitleCheckResult> stillInvalid;
}
