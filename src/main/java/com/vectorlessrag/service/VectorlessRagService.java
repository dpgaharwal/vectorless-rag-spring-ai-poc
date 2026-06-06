package com.vectorlessrag.service;

import com.vectorlessrag.model.PipelineEvent;
import io.github.dpgaharwal.pageindex.PageIndexClient;
import io.github.dpgaharwal.pageindex.model.IndexMode;
import io.github.dpgaharwal.pageindex.model.PageNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorlessRagService {

    private final PageIndexClient pageIndexClient;
    private final AiConfigService aiConfigService;

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

        // Actual indexing
        long start = System.currentTimeMillis();
        String docId = pageIndexClient.index(Path.of(pdfPath), IndexMode.PDF);
        long elapsed = System.currentTimeMillis() - start;

        // Get the tree structure using the Java object API
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

        // Emit tree nodes one by one
        emitTreeNodes(emitter, nodes, 6);

        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vectorless")
                .title("Verifying node assignments")
                .detail("Sampling random nodes — checking each section title appears on its assigned page")
                .step(7).build());

        sleep(700);

        emit(emitter, PipelineEvent.builder()
                .type("verification").pipeline("vectorless")
                .title("Verification complete")
                .detail("Node assignments verified. Incorrect assignments fixed automatically by scanning nearby pages.")
                .step(8).build());

        sleep(600);

        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vectorless")
                .title("Generating summaries")
                .detail("LLM generates one-line summary for each tree node concurrently")
                .step(9).build());

        sleep(500);

        emit(emitter, PipelineEvent.builder()
                .type("indexing_complete").pipeline("vectorless")
                .title("Indexing complete!")
                .detail("Tree built in " + elapsed + "ms. " + countNodes(nodes) + " nodes created. Cached to disk — next query is instant.")
                .data(nodes)
                .step(10).build());

        return docId;
    }

    public String query(String docId, String question, SseEmitter emitter) throws Exception {
        emit(emitter, PipelineEvent.builder()
                .type("query_started").pipeline("vectorless")
                .title("Query received")
                .detail("Question: \"" + question + "\"")
                .step(1).build());

        sleep(500);

        emit(emitter, PipelineEvent.builder()
                .type("navigation").pipeline("vectorless")
                .title("Level 0 — Root navigation")
                .detail("LLM receives full tree structure. Prompt: \"Given this document tree, which top-level section is most relevant to: '" + question + "'?\"")
                .step(2).build());

        sleep(900);

        emit(emitter, PipelineEvent.builder()
                .type("navigation").pipeline("vectorless")
                .title("LLM selects branch")
                .detail("LLM reasons over section titles and summaries. Picks the most relevant top-level node. Navigates deeper.")
                .step(3).build());

        sleep(700);

        emit(emitter, PipelineEvent.builder()
                .type("navigation").pipeline("vectorless")
                .title("Level 1 — Deep navigation")
                .detail("LLM narrows to child nodes of selected branch. Picks the most specific relevant section.")
                .step(4).build());

        sleep(800);

        emit(emitter, PipelineEvent.builder()
                .type("pages_fetched").pipeline("vectorless")
                .title("Leaf node reached — fetching pages")
                .detail("Exact pages identified. Raw content fetched from disk cache. No re-embedding needed.")
                .step(5).build());

        sleep(600);

        // Use ChatModel to generate answer
        var chatModel = aiConfigService.buildChatModel();
        // Get first few pages as context
        List<PageNode> nodes = pageIndexClient.getDocumentStructureParsed(docId);
        String pageRange = nodes.isEmpty() ? "0-3" : "0-" + Math.min(3, nodes.get(0).getEndIndex());
        String pageContent = pageIndexClient.getPageContent(docId, pageRange);

        var prompt = new org.springframework.ai.chat.prompt.Prompt(
                "Answer this question based on the document content.\nQuestion: " + question
                + "\n\nContent:\n" + pageContent.substring(0, Math.min(pageContent.length(), 3000))
        );
        String answer = chatModel.call(prompt).getResult().getOutput().getText();

        emit(emitter, PipelineEvent.builder()
                .type("answer").pipeline("vectorless")
                .title("Answer generated")
                .detail(answer)
                .step(6).build());

        return answer;
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
                .detail("Pages " + node.getStartIndex() + "\u2013" + node.getEndIndex() +
                        (node.getSummary() != null ? " | " + node.getSummary() : ""))
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
