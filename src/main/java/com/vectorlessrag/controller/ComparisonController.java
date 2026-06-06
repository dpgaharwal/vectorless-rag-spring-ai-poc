package com.vectorlessrag.controller;

import com.vectorlessrag.model.PipelineEvent;
import com.vectorlessrag.service.AiConfigService;
import com.vectorlessrag.service.VectorRagService;
import com.vectorlessrag.service.VectorlessRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/compare")
@RequiredArgsConstructor
public class ComparisonController {

    private final VectorlessRagService vectorlessService;
    private final VectorRagService vectorService;
    private final AiConfigService aiConfigService;

    private final Map<String, String> documentIds = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(value = "/index", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter index(@RequestParam String pdfPath) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        if (!aiConfigService.isConfigured()) {
            executor.submit(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(PipelineEvent.builder()
                                    .type("error")
                                    .title("API key not configured")
                                    .detail("Please configure your API key first.")
                                    .build()));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        }

        executor.submit(() -> {
            try {
                // Run vectorless first (gets docId), then vector uses same docId
                String docId = vectorlessService.indexDocument(pdfPath, emitter);
                vectorService.indexDocument(pdfPath, docId, emitter);
                documentIds.put(docId, docId);

                emitter.send(SseEmitter.event().name("session")
                        .data(Map.of("docId", docId)));
                emitter.complete();
            } catch (Exception e) {
                log.error("Indexing error", e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(PipelineEvent.builder()
                                    .type("error")
                                    .title("Indexing error")
                                    .detail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                                    .build()));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    @GetMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@RequestParam String docId, @RequestParam String question) {
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.submit(() -> {
            try {
                // Run both query pipelines concurrently
                ExecutorService queryPool = Executors.newFixedThreadPool(2);

                var vlessFuture = queryPool.submit(() -> {
                    try {
                        return vectorlessService.query(docId, question, emitter);
                    } catch (Exception e) {
                        log.error("Vectorless query error", e);
                        return "Error: " + e.getMessage();
                    }
                });

                var vectFuture = queryPool.submit(() -> {
                    try {
                        return vectorService.query(docId, question, emitter);
                    } catch (Exception e) {
                        log.error("Vector query error", e);
                        return "Error: " + e.getMessage();
                    }
                });

                String vlessAnswer = vlessFuture.get();
                String vectAnswer = vectFuture.get();
                queryPool.shutdown();

                emitter.send(SseEmitter.event().name("comparison_complete")
                        .data(Map.of(
                                "vectorlessAnswer", vlessAnswer,
                                "vectorAnswer", vectAnswer
                        )));
                emitter.complete();
            } catch (Exception e) {
                log.error("Query error", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    log.error("Emitter error", ex);
                }
            }
        });

        return emitter;
    }
}
