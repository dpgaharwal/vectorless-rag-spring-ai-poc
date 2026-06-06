package com.vectorlessrag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vectorlessrag.model.PipelineEvent;
import io.github.dpgaharwal.pageindex.PageIndexClient;
import io.github.dpgaharwal.pageindex.model.IndexMode;
import io.github.dpgaharwal.pageindex.model.PageNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorlessRagService {

    private final PageIndexClient pageIndexClient;
    private final AiConfigService aiConfigService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ──────────────────────────────────────────────────────────────
    // INDEXING
    // ──────────────────────────────────────────────────────────────

    public String indexDocument(String pdfPath, SseEmitter emitter) throws Exception {
        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vectorless")
                .title("Starting Vectorless RAG")
                .detail("Using PageIndex algorithm — no vector database needed")
                .step(1).build());
        sleep(800);

        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vectorless")
                .title("Parsing PDF pages")
                .detail("Reading document page by page and tagging each with physical_index markers")
                .step(2).build());
        sleep(600);

        long start = System.currentTimeMillis();
        String docId = pageIndexClient.index(Path.of(pdfPath), IndexMode.PDF);
        long elapsed = System.currentTimeMillis() - start;

        List<PageNode> nodes = pageIndexClient.getDocumentStructureParsed(docId);

        emit(emitter, PipelineEvent.builder()
                .type("pages_parsed").pipeline("vectorless")
                .title("Pages parsed & tagged")
                .detail("Each page tagged with <physical_index_N> markers for unambiguous LLM identification")
                .step(3).build());
        sleep(700);

        emit(emitter, PipelineEvent.builder()
                .type("toc_detected").pipeline("vectorless")
                .title("TOC Detection")
                .detail("LLM analyzes first 20 pages to detect Table of Contents. Selects mode: WITH_PAGE_NUMBERS, NO_PAGE_NUMBERS, or NO_TOC")
                .step(4).build());
        sleep(800);

        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vectorless")
                .title("Building tree structure")
                .detail("LLM generates hierarchical section structure from document content")
                .step(5).build());
        sleep(600);

        emitTreeNodes(emitter, nodes, 6);

        emit(emitter, PipelineEvent.builder()
                .type("verification").pipeline("vectorless")
                .title("Verification complete")
                .detail("Node assignments verified. Incorrect assignments fixed automatically by scanning nearby pages.")
                .step(7).build());
        sleep(600);

        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vectorless")
                .title("Generating summaries")
                .detail("LLM generates one-line summary for each tree node concurrently")
                .step(8).build());
        sleep(500);

        emit(emitter, PipelineEvent.builder()
                .type("indexing_complete").pipeline("vectorless")
                .title("Indexing complete!")
                .detail("Tree built in " + elapsed + "ms. " + countNodes(nodes) + " nodes created. Cached to disk.")
                .data(Map.of("nodes", nodes, "elapsedMs", elapsed, "nodeCount", countNodes(nodes)))
                .step(9).build());

        return docId;
    }

    // ──────────────────────────────────────────────────────────────
    // QUERY — real LLM tree navigation
    // ──────────────────────────────────────────────────────────────

    public String query(String docId, String question, SseEmitter emitter) throws Exception {
        emit(emitter, PipelineEvent.builder()
                .type("query_started").pipeline("vectorless")
                .title("Query received")
                .detail("Question: \"" + question + "\"")
                .step(1).build());
        sleep(400);

        List<PageNode> nodes = pageIndexClient.getDocumentStructureParsed(docId);

        if (nodes.isEmpty()) {
            emit(emitter, PipelineEvent.builder()
                    .type("error").pipeline("vectorless")
                    .title("No document tree found")
                    .detail("Document not indexed or tree empty: " + docId)
                    .step(2).build());
            return "Error: document not indexed.";
        }

        // Emit tree_overview so the frontend can draw the SVG tree
        emit(emitter, PipelineEvent.builder()
                .type("tree_overview").pipeline("vectorless")
                .title("Document tree loaded — " + nodes.size() + " root sections")
                .detail("Rendering full document hierarchy for navigation")
                .data(Map.of("nodes", nodes))
                .step(2).build());
        sleep(800);

        // Navigate the tree with LLM guidance
        PageNode leaf = navigateTree(nodes, question, emitter, 3);

        // Fetch pages for the selected leaf
        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vectorless")
                .title("Fetching pages for selected section")
                .detail("Loading pages " + leaf.getStartIndex() + "–" + leaf.getEndIndex() + " from disk cache")
                .step(8).build());
        sleep(400);

        String pageRange = leaf.getStartIndex() + "-" + leaf.getEndIndex();
        String pageContentJson = pageIndexClient.getPageContent(docId, pageRange);
        String contextText = extractTextFromPageJson(pageContentJson);
        int charCount = contextText.length();
        String preview = contextText.substring(0, Math.min(200, charCount));

        emit(emitter, PipelineEvent.builder()
                .type("pages_fetched").pipeline("vectorless")
                .title("Pages fetched — p." + leaf.getStartIndex() + " → " + leaf.getEndIndex())
                .detail(charCount + " characters of raw content retrieved from disk")
                .data(Map.of("pageRange", pageRange, "charCount", charCount, "preview", preview))
                .step(9).build());
        sleep(500);

        // Context assembled
        int tokenEstimate = charCount / 4;
        emit(emitter, PipelineEvent.builder()
                .type("context_assembled").pipeline("vectorless")
                .title("Context assembled — ~" + tokenEstimate + " tokens")
                .detail("Page content ready for LLM. " + charCount + " chars ≈ " + tokenEstimate + " tokens.")
                .data(Map.of("contextText", contextText, "charCount", charCount, "tokenEstimate", tokenEstimate))
                .step(10).build());
        sleep(400);

        // Build QA prompt
        String systemPrompt = "You are a helpful assistant. Answer the question using ONLY the provided document context. If the answer is not in the context, say \"Not found in document.\"";
        String truncatedContext = contextText.substring(0, Math.min(contextText.length(), 6000));
        String contextPreview = contextText.substring(0, Math.min(400, contextText.length()));

        emit(emitter, PipelineEvent.builder()
                .type("llm_prompt_sent").pipeline("vectorless")
                .title("Prompt sent to LLM")
                .detail("System prompt + " + charCount + " char context + question → LLM")
                .data(Map.of(
                        "systemPrompt", systemPrompt,
                        "contextPreview", contextPreview,
                        "question", question,
                        "totalChars", systemPrompt.length() + truncatedContext.length() + question.length()
                ))
                .step(11).build());
        sleep(300);

        // Call LLM
        String fullPrompt = systemPrompt + "\n\nContext:\n" + truncatedContext + "\n\nQuestion: " + question + "\n\nAnswer:";
        var chatModel = aiConfigService.buildChatModel();
        String answer = chatModel.call(new Prompt(fullPrompt)).getResult().getOutput().getText();

        emit(emitter, PipelineEvent.builder()
                .type("llm_response_received").pipeline("vectorless")
                .title("LLM response received — " + answer.length() + " characters")
                .detail("Response received from LLM")
                .data(Map.of("responseText", answer))
                .step(12).build());
        sleep(200);

        emit(emitter, PipelineEvent.builder()
                .type("answer").pipeline("vectorless")
                .title("Answer generated")
                .detail(answer)
                .step(13).build());

        return answer;
    }

    // ──────────────────────────────────────────────────────────────
    // TREE NAVIGATION
    // ──────────────────────────────────────────────────────────────

    private PageNode navigateTree(List<PageNode> nodes, String question,
                                   SseEmitter emitter, int startStep) throws IOException, InterruptedException {
        List<PageNode> currentLevel = nodes;
        int depth = 0;
        PageNode selected = nodes.get(0);

        while (currentLevel != null && !currentLevel.isEmpty()) {
            // Build candidates for UI (HashMap avoids Map.of() mixed-type inference issues)
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (PageNode n : currentLevel) {
                Map<String, Object> c = new HashMap<>();
                c.put("nodeId",     n.getNodeId() != null ? n.getNodeId() : String.valueOf(currentLevel.indexOf(n)));
                c.put("title",      n.getTitle() != null ? n.getTitle() : "Section");
                c.put("startIndex", n.getStartIndex());
                c.put("endIndex",   n.getEndIndex());
                c.put("summary",    n.getSummary() != null ? n.getSummary() : "");
                candidates.add(c);
            }

            Map<String, Object> navLevelData = new HashMap<>();
            navLevelData.put("depth", depth);
            navLevelData.put("candidates", candidates);
            emit(emitter, PipelineEvent.builder()
                    .type("nav_level").pipeline("vectorless")
                    .title("Level " + depth + " — evaluating " + currentLevel.size() + " sections")
                    .detail("LLM will select the most relevant section for: \"" + question + "\"")
                    .data(navLevelData)
                    .step(startStep + depth).build());
            sleep(600);

            // Single node — just select it
            if (currentLevel.size() == 1) {
                selected = currentLevel.get(0);
                emitNodeSelected(emitter, selected, depth, startStep);
                sleep(400);
                if (selected.isLeaf()) break;
                currentLevel = selected.getNodes();
                depth++;
                continue;
            }

            // Build nav prompt
            StringBuilder sb = new StringBuilder();
            for (PageNode n : currentLevel) {
                sb.append(String.format("nodeId: %s | title: %s | pages: %d-%d | summary: %s\n",
                        n.getNodeId() != null ? n.getNodeId() : "?",
                        n.getTitle(),
                        n.getStartIndex(), n.getEndIndex(),
                        n.getSummary() != null ? n.getSummary() : ""));
            }
            String navPrompt = "You are navigating a document to answer the question: \"" + question + "\"\n\n"
                    + "Available sections:\n" + sb
                    + "\nWhich section most likely contains the answer? "
                    + "Respond with ONLY the nodeId value (e.g. \"0003\"). Nothing else.";

            String promptPreview = navPrompt.length() > 600 ? navPrompt.substring(0, 600) + "…" : navPrompt;
            emit(emitter, PipelineEvent.builder()
                    .type("nav_llm_prompt").pipeline("vectorless")
                    .title("Navigation prompt → LLM (level " + depth + ")")
                    .detail(promptPreview)
                    .step(startStep + depth).build());
            sleep(300);

            // Call LLM for navigation
            var chatModel = aiConfigService.buildChatModel();
            String navResponse = chatModel.call(new Prompt(navPrompt))
                    .getResult().getOutput().getText().trim();
            String pickedId = navResponse.replaceAll("[^0-9]", "");
            log.info("Tree navigation depth={} LLM picked nodeId={}", depth, pickedId);

            // Find matching node (fallback to first)
            String finalPickedId = pickedId;
            selected = currentLevel.stream()
                    .filter(n -> finalPickedId.equals(n.getNodeId()))
                    .findFirst()
                    .orElse(currentLevel.get(0));

            emitNodeSelected(emitter, selected, depth, startStep);
            sleep(700);

            if (selected.isLeaf() || selected.getNodes() == null || selected.getNodes().isEmpty()) {
                break;
            }
            currentLevel = selected.getNodes();
            depth++;
        }

        return selected;
    }

    private void emitNodeSelected(SseEmitter emitter, PageNode node, int depth, int startStep) throws IOException {
        Map<String, Object> d = new HashMap<>();
        d.put("nodeId",     node.getNodeId() != null ? node.getNodeId() : "");
        d.put("title",      node.getTitle()  != null ? node.getTitle()  : "");
        d.put("startIndex", node.getStartIndex());
        d.put("endIndex",   node.getEndIndex());
        d.put("depth",      depth);
        emit(emitter, PipelineEvent.builder()
                .type("node_selected").pipeline("vectorless")
                .title("Level " + depth + " → \"" + node.getTitle() + "\"")
                .detail("Pages " + node.getStartIndex() + "–" + node.getEndIndex()
                        + (node.getSummary() != null ? "  |  " + node.getSummary() : ""))
                .data(d)
                .step(startStep + depth + 1).build());
    }

    // ──────────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────────

    private String extractTextFromPageJson(String pageContentJson) {
        try {
            JsonNode array = MAPPER.readTree(pageContentJson);
            StringBuilder sb = new StringBuilder();
            for (JsonNode node : array) {
                if (node.has("content")) {
                    sb.append(node.get("content").asText()).append("\n\n");
                }
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? pageContentJson : result;
        } catch (Exception e) {
            log.warn("Could not parse page content JSON, using raw: {}", e.getMessage());
            return pageContentJson;
        }
    }

    private void emitTreeNodes(SseEmitter emitter, List<PageNode> nodes, int startStep) throws IOException, InterruptedException {
        int step = startStep;
        for (PageNode node : nodes) {
            emitNode(emitter, node, 0, step++);
            sleep(300);
            if (node.getNodes() != null) {
                for (PageNode child : node.getNodes()) {
                    emitNode(emitter, child, 1, step++);
                    sleep(200);
                }
            }
        }
    }

    private void emitNode(SseEmitter emitter, PageNode node, int depth, int step) throws IOException {
        String indent = depth == 0 ? "Root" : "Child";
        emit(emitter, PipelineEvent.builder()
                .type("tree_node").pipeline("vectorless")
                .title(indent + ": " + node.getTitle())
                .detail("Pages " + node.getStartIndex() + "–" + node.getEndIndex()
                        + (node.getSummary() != null ? " | " + node.getSummary() : ""))
                .data(node)
                .step(step).build());
    }

    private int countNodes(List<PageNode> nodes) {
        int count = 0;
        for (PageNode n : nodes) {
            count++;
            if (n.getNodes() != null) count += n.getNodes().size();
        }
        return count;
    }

    private void emit(SseEmitter emitter, PipelineEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .name(event.getPipeline() + "_" + event.getType())
                .data(event));
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
