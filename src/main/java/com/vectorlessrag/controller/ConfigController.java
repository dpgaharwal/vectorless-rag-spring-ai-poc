package com.vectorlessrag.controller;

import com.vectorlessrag.service.AiConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final AiConfigService aiConfigService;

    @PostMapping
    public ResponseEntity<?> configure(@RequestBody Map<String, String> body) {
        String apiKey = body.get("apiKey");
        String model = body.get("model");

        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "API key is required"));
        }

        aiConfigService.setApiKey(apiKey.trim());
        if (model != null && !model.isBlank()) {
            aiConfigService.setModel(model.trim());
        }

        return ResponseEntity.ok(Map.of(
                "status", "configured",
                "model", aiConfigService.getModel()
        ));
    }

    @GetMapping("/models")
    public ResponseEntity<?> getModels() {
        return ResponseEntity.ok(Map.of("models", List.of(
                "gpt-4o-mini",
                "gpt-4o",
                "gpt-4o-2024-11-20",
                "gpt-3.5-turbo"
        )));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(Map.of(
                "configured", aiConfigService.isConfigured(),
                "model", aiConfigService.getModel()
        ));
    }
}
