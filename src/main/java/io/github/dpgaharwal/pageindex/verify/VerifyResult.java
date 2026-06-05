package io.github.dpgaharwal.pageindex.verify;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class VerifyResult {
    private double accuracy;
    private List<TitleCheckResult> incorrectResults;

    public boolean isPerfect() {
        return incorrectResults.isEmpty();
    }
}
