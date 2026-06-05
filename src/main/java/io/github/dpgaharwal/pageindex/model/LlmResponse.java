package io.github.dpgaharwal.pageindex.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LlmResponse {
    private String content;
    private FinishReason finishReason;

    public enum FinishReason {
        FINISHED, MAX_OUTPUT_REACHED, ERROR
    }
}
