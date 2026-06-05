package io.github.dpgaharwal.pageindex.config;

import lombok.Builder;
import lombok.Data;

/**
 * Per-call runtime overrides. Null fields fall back to PageIndexProperties defaults.
 * Use PageIndexConfig.from(props) to get a fully-resolved instance.
 */
@Data
@Builder
public class PageIndexConfig {
    private String model;
    private String retrieveModel;
    private Integer tocCheckPageNum;
    private Integer maxPageNumEachNode;
    private Integer maxTokenNumEachNode;
    private Boolean addNodeId;
    private Boolean addNodeSummary;
    private Boolean addDocDescription;
    private Boolean addNodeText;

    public static PageIndexConfig from(PageIndexProperties props) {
        return PageIndexConfig.builder()
                .model(props.getModel())
                .retrieveModel(props.effectiveRetrieveModel())
                .tocCheckPageNum(props.getTocCheckPageNum())
                .maxPageNumEachNode(props.getMaxPageNumEachNode())
                .maxTokenNumEachNode(props.getMaxTokenNumEachNode())
                .addNodeId(props.isAddNodeId())
                .addNodeSummary(props.isAddNodeSummary())
                .addDocDescription(props.isAddDocDescription())
                .addNodeText(props.isAddNodeText())
                .build();
    }

    /** User-supplied overrides win; nulls in override are ignored. */
    public PageIndexConfig mergeWith(PageIndexConfig override) {
        if (override == null) return this;
        return PageIndexConfig.builder()
                .model(override.model != null ? override.model : this.model)
                .retrieveModel(override.retrieveModel != null ? override.retrieveModel : this.retrieveModel)
                .tocCheckPageNum(override.tocCheckPageNum != null ? override.tocCheckPageNum : this.tocCheckPageNum)
                .maxPageNumEachNode(override.maxPageNumEachNode != null ? override.maxPageNumEachNode : this.maxPageNumEachNode)
                .maxTokenNumEachNode(override.maxTokenNumEachNode != null ? override.maxTokenNumEachNode : this.maxTokenNumEachNode)
                .addNodeId(override.addNodeId != null ? override.addNodeId : this.addNodeId)
                .addNodeSummary(override.addNodeSummary != null ? override.addNodeSummary : this.addNodeSummary)
                .addDocDescription(override.addDocDescription != null ? override.addDocDescription : this.addDocDescription)
                .addNodeText(override.addNodeText != null ? override.addNodeText : this.addNodeText)
                .build();
    }

    public String effectiveRetrieveModel() {
        return retrieveModel != null && !retrieveModel.isBlank() ? retrieveModel : model;
    }
}
