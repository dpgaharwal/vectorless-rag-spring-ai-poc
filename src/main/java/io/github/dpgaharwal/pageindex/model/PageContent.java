package io.github.dpgaharwal.pageindex.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageContent {
    private int page;
    private String content;
}
