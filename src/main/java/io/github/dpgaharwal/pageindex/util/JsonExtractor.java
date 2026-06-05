package io.github.dpgaharwal.pageindex.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans and parses raw LLM JSON responses.
 * Mirrors extract_json() and get_json_content() from utils.py.
 */
@Component
@RequiredArgsConstructor
public class JsonExtractor {

    private final ObjectMapper objectMapper;

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL);
    private static final Pattern ARRAY_PATTERN = Pattern.compile("(\\[.*])", Pattern.DOTALL);
    private static final Pattern OBJECT_PATTERN = Pattern.compile("(\\{.*})", Pattern.DOTALL);

    public JsonNode extract(String raw) {
        if (raw == null || raw.isBlank()) return objectMapper.createObjectNode();
        String cleaned = clean(raw);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            String fixed = fixJson(cleaned);
            try {
                return objectMapper.readTree(fixed);
            } catch (Exception e2) {
                return objectMapper.createObjectNode();
            }
        }
    }

    public <T> T extractAs(String raw, Class<T> type) {
        JsonNode node = extract(raw);
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            return null;
        }
    }

    public <T> T extractAs(String raw, TypeReference<T> type) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = clean(raw);
        try {
            return objectMapper.readValue(cleaned, type);
        } catch (Exception e) {
            String fixed = fixJson(cleaned);
            try {
                return objectMapper.readValue(fixed, type);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** Extract the JSON array or object embedded in an LLM response. */
    public String extractJsonString(String raw) {
        if (raw == null) return "{}";
        // Try ```json fence first
        Matcher fenceMatcher = JSON_FENCE.matcher(raw);
        if (fenceMatcher.find()) return fenceMatcher.group(1).trim();
        // Try bare array
        Matcher arrayMatcher = ARRAY_PATTERN.matcher(raw);
        if (arrayMatcher.find()) return arrayMatcher.group(1).trim();
        // Try bare object
        Matcher objectMatcher = OBJECT_PATTERN.matcher(raw);
        if (objectMatcher.find()) return objectMatcher.group(1).trim();
        return raw.trim();
    }

    private String clean(String raw) {
        String s = extractJsonString(raw);
        // Python None → null, True/False → true/false
        s = s.replace(": None", ": null")
             .replace(":None", ":null")
             .replace(": True", ": true")
             .replace(": False", ": false");
        return s;
    }

    private String fixJson(String json) {
        // Remove trailing commas before ] or }
        return json.replaceAll(",\\s*([}\\]])", "$1");
    }
}
