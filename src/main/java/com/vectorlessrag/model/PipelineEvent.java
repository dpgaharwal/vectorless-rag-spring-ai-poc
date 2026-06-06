package com.vectorlessrag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineEvent {
    private String type;      // event type
    private String pipeline;  // "vectorless" or "vector"
    private String title;     // short label shown in UI
    private String detail;    // longer detail / prompt / response text
    private Object data;      // any extra structured data
    private int step;         // step number
    private long delayMs;     // how long to wait before next event
}
