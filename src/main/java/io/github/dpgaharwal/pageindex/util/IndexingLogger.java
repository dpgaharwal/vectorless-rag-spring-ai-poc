package io.github.dpgaharwal.pageindex.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-indexing-session structured JSON logger.
 * Mirrors JsonLogger from utils.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexingLogger {

    private final ObjectMapper objectMapper;

    public IndexingSession startSession(String docName) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String safeName = docName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        Path logPath = Paths.get("logs", safeName + "_" + timestamp + ".json");
        return new IndexingSession(logPath, objectMapper);
    }

    public static class IndexingSession implements AutoCloseable {
        private final Path logPath;
        private final ObjectMapper mapper;
        private final List<Map<String, Object>> entries = new ArrayList<>();

        IndexingSession(Path logPath, ObjectMapper mapper) {
            this.logPath = logPath;
            this.mapper = mapper;
        }

        public void info(String message) {
            entries.add(Map.of("level", "INFO", "ts", Instant.now().toString(), "msg", message));
            log.info("[PageIndex] {}", message);
        }

        public void info(Map<String, Object> data) {
            entries.add(data);
        }

        public void error(String message) {
            entries.add(Map.of("level", "ERROR", "ts", Instant.now().toString(), "msg", message));
            log.error("[PageIndex] {}", message);
        }

        @Override
        public void close() {
            try {
                Files.createDirectories(logPath.getParent());
                mapper.writerWithDefaultPrettyPrinter().writeValue(logPath.toFile(), entries);
            } catch (IOException e) {
                log.warn("Could not write index log to {}: {}", logPath, e.getMessage());
            }
        }
    }
}
