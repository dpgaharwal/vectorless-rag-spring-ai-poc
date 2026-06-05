package io.github.dpgaharwal.pageindex.markdown;

import io.github.dpgaharwal.pageindex.config.PageIndexConfig;
import io.github.dpgaharwal.pageindex.features.NodeFeatureEnricher;
import io.github.dpgaharwal.pageindex.model.DocumentResult;
import io.github.dpgaharwal.pageindex.model.PageContent;
import io.github.dpgaharwal.pageindex.model.PageNode;
import io.github.dpgaharwal.pageindex.util.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Full Markdown indexing pipeline: parse → (thin) → build tree → enrich.
 * Mirrors md_to_tree() from page_index_md.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkdownIndexer {

    private final MarkdownParser parser;
    private final NodeFeatureEnricher enricher;
    private final LlmGateway llm;

    private static final int SUMMARY_TOKEN_THRESHOLD = 100;
    private static final int MIN_THINNING_THRESHOLD = 50;

    /**
     * Index a Markdown file into a DocumentResult.
     * Mirrors md_to_tree().
     */
    public DocumentResult index(Path mdPath, PageIndexConfig config, boolean thinning) throws IOException {
        String docName = mdPath.getFileName().toString();
        log.info("Indexing markdown: {}", docName);

        // Step 1: Parse headers
        List<MarkdownNode> nodes = parser.parse(mdPath);

        // Step 2: Optional thinning
        if (thinning) {
            nodes = parser.thin(nodes, MIN_THINNING_THRESHOLD);
        }

        if (nodes.isEmpty()) {
            log.warn("No headers found in {}, creating single root node", docName);
            return DocumentResult.builder().docName(docName)
                    .structure(List.of(PageNode.builder()
                            .title(docName).startIndex(0).endIndex(0).build()))
                    .build();
        }

        // Step 3: Build tree from flat header list
        List<PageNode> tree = buildTree(nodes);

        // Step 4: Assign node IDs
        if (Boolean.TRUE.equals(config.getAddNodeId())) {
            enricher.writeNodeIds(tree);
        }

        // Step 5: Generate summaries (use text directly if short enough)
        if (Boolean.TRUE.equals(config.getAddNodeSummary())) {
            generateMarkdownSummaries(tree);
        }

        // Step 6: Document description
        String description = null;
        if (Boolean.TRUE.equals(config.getAddDocDescription())) {
            description = enricher.generateDocDescription(tree);
        }

        // Step 7: Remove text if not requested
        if (!Boolean.TRUE.equals(config.getAddNodeText())) {
            enricher.removeNodeText(tree);
        }

        return DocumentResult.builder()
                .docName(docName)
                .docDescription(description)
                .structure(tree)
                .build();
    }

    /**
     * Build a hierarchy from a flat list of MarkdownNodes using a level-based stack.
     * Level 1 (#) → root nodes, level 2 (##) → children of level 1, etc.
     */
    private List<PageNode> buildTree(List<MarkdownNode> nodes) {
        List<PageNode> roots = new ArrayList<>();
        Stack<PageNode> stack = new Stack<>();
        Stack<Integer> levelStack = new Stack<>();

        for (int i = 0; i < nodes.size(); i++) {
            MarkdownNode md = nodes.get(i);
            PageNode node = PageNode.builder()
                    .title(md.getTitle())
                    .startIndex(md.getLineNum())
                    .endIndex(i + 1 < nodes.size() ? nodes.get(i + 1).getLineNum() - 1 : Integer.MAX_VALUE)
                    .text(md.getText())
                    .nodes(new ArrayList<>())
                    .build();

            // Pop stack until we find a parent with lower level
            while (!stack.isEmpty() && levelStack.peek() >= md.getLevel()) {
                stack.pop();
                levelStack.pop();
            }

            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().getNodes().add(node);
            }

            stack.push(node);
            levelStack.push(md.getLevel());
        }

        // Clean up empty node lists
        cleanEmptyNodes(roots);
        return roots;
    }

    private void cleanEmptyNodes(List<PageNode> nodes) {
        if (nodes == null) return;
        for (PageNode node : nodes) {
            if (node.getNodes() != null && node.getNodes().isEmpty()) {
                node.setNodes(null);
            }
            cleanEmptyNodes(node.getNodes());
        }
    }

    private void generateMarkdownSummaries(List<PageNode> nodes) {
        if (nodes == null) return;
        for (PageNode node : nodes) {
            if (node.getText() != null && !node.getText().isBlank()) {
                // For short sections, use text directly as summary
                if (node.getText().length() < SUMMARY_TOKEN_THRESHOLD * 4) {
                    node.setSummary(node.getText().substring(0, Math.min(200, node.getText().length())).trim());
                } else {
                    String prompt = "Summarize in one sentence: " + node.getText().substring(0, 1000);
                    node.setSummary(llm.call(prompt).getContent().trim());
                }
            }
            generateMarkdownSummaries(node.getNodes());
        }
    }

    /** Convert MarkdownNodes to PageContent for storage. */
    public List<PageContent> toPageContents(List<MarkdownNode> nodes) {
        List<PageContent> pages = new ArrayList<>();
        for (MarkdownNode node : nodes) {
            pages.add(new PageContent(node.getLineNum(), node.getText() != null ? node.getText() : ""));
        }
        return pages;
    }

    /** Expose the injected parser for use by callers that need raw node parsing. */
    public MarkdownParser getParser() {
        return parser;
    }
}
