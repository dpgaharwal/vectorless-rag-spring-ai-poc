package io.github.dpgaharwal.pageindex.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResult {
    @JsonProperty("doc_name")
    private String docName;

    @JsonProperty("doc_description")
    private String docDescription;

    private List<PageNode> structure;
}
