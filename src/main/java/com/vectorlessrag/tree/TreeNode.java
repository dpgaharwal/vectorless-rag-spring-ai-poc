package com.vectorlessrag.tree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TreeNode {

  @JsonProperty("node_id")
  private String nodeId;

  @JsonProperty("title")
  private String title;

  @JsonProperty("summary")
  private String summary;

  @JsonProperty("start_index") // page start (0-based)
  private int startIndex;

  @JsonProperty("end_index") // page end (exclusive)
  private int endIndex;

  @JsonProperty("nodes")
  @Builder.Default
  private List<TreeNode> children = new ArrayList<>();

  // helper — is this a leaf node (no children)
  public boolean isLeaf() {
    return children == null || children.isEmpty();
  }

  // helper — how many pages does this node cover.
  public int pageCount() {
    return endIndex - startIndex;
  }
}
