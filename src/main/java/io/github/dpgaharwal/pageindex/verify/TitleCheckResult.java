package io.github.dpgaharwal.pageindex.verify;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TitleCheckResult {
    private int listIndex;
    private String answer;    // "yes" or "no"
    private String title;
    private Integer physicalIndex;

    public boolean isCorrect() {
        return "yes".equalsIgnoreCase(answer);
    }
}
