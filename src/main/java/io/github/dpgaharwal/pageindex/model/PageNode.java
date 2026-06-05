package io.github.dpgaharwal.pageindex.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A node in the hierarchical document tree.
 * JSON keys match the Python PageIndex output for wire compatibility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageNode {
    @JsonProperty("node_id")
    private String nodeId;

    private String title;

    @JsonProperty("start_index")
    private int startIndex;

    @JsonProperty("end_index")
    private int endIndex;

    private String summary;

    @JsonProperty("prefix_summary")
    private String prefixSummary;

    private String text;

    private String description;

    /** Child nodes (null / empty for leaf nodes) */
    private List<PageNode> nodes;

    public boolean isLeaf() {
        return nodes == null || nodes.isEmpty();
    }

    public int pageCount() {
        return endIndex - startIndex + 1;
    }
}
