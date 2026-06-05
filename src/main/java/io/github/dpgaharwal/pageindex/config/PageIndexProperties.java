package io.github.dpgaharwal.pageindex.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@Data
@ConfigurationProperties(prefix = "pageindex")
public class PageIndexProperties {
    private String model = "gpt-4o-2024-11-20";
    private String retrieveModel;
    private int tocCheckPageNum = 20;
    private int maxPageNumEachNode = 10;
    private int maxTokenNumEachNode = 20000;
    private boolean addNodeId = true;
    private boolean addNodeSummary = true;
    private boolean addDocDescription = false;
    private boolean addNodeText = false;
    private String storeDir = "pageindex-store/";
    private int maxLlmRetries = 10;
    private int llmRetryDelayMs = 1000;

    @NestedConfigurationProperty
    private Concurrency concurrency = new Concurrency();

    @Data
    public static class Concurrency {
        private int poolSize = 10;
    }

    public String effectiveRetrieveModel() {
        return retrieveModel != null && !retrieveModel.isBlank() ? retrieveModel : model;
    }
}
