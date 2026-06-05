package io.github.dpgaharwal.pageindex.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentEntry {
    private String id;

    /** "pdf" or "md" */
    private String type;

    @JsonProperty("doc_name")
    private String docName;

    @JsonProperty("doc_description")
    private String docDescription;

    /** Original file path */
    private String path;

    /** PDF only */
    @JsonProperty("page_count")
    private Integer pageCount;

    /** Markdown only */
    @JsonProperty("line_count")
    private Integer lineCount;

    /** Loaded lazily from disk */
    private transient List<PageNode> structure;

    /** Loaded lazily from disk */
    private transient List<PageContent> pages;
}
