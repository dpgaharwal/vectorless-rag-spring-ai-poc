package io.github.dpgaharwal.pageindex.markdown;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkdownNode {
    private String title;
    private int lineNum;       // 0-based line number of the header
    private int level;         // 1 = #, 2 = ##, 3 = ###, etc.
    private String text;       // content from this header to next header
    private int textTokenCount;
}
