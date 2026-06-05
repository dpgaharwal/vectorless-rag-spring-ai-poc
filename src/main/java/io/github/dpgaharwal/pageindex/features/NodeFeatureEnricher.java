package io.github.dpgaharwal.pageindex.features;

import io.github.dpgaharwal.pageindex.model.PageNode;
import io.github.dpgaharwal.pageindex.pdf.PageData;
import io.github.dpgaharwal.pageindex.util.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Adds optional features to tree nodes: IDs, text, summaries, document description.
 * Mirrors write_node_id(), add_node_text(), generate_summaries_for_structure(),
 * and generate_doc_description() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeFeatureEnricher {

    private final LlmGateway llm;

    private static final String SUMMARY_PROMPT = """
            Write a concise one-sentence summary of the following document section.
            Section title: %s

            Content:
            %s

            One-sentence summary:
            """;

    private static final String DESCRIPTION_PROMPT = """
            Based on the following document structure, write a single sentence describing what this document is about.

            Structure:
            %s

            One-sentence description:
            """;

    /**
     * Assign sequential 4-digit zero-padded IDs depth-first: "0001", "0002", ...
     * Mirrors write_node_id().
     */
    public void writeNodeIds(List<PageNode> nodes) {
        int[] counter = {1};
        for (PageNode node : nodes) {
            writeNodeIdsRecursive(node, counter);
        }
    }

    private void writeNodeIdsRecursive(PageNode node, int[] counter) {
        node.setNodeId(String.format("%04d", counter[0]++));
        if (node.getNodes() != null) {
            for (PageNode child : node.getNodes()) {
                writeNodeIdsRecursive(child, counter);
            }
        }
    }

    /**
     * Populate each node's text field with the concatenated raw page text for its range.
     * Mirrors add_node_text().
     */
    public void addNodeText(List<PageNode> nodes, List<PageData> pages) {
        for (PageNode node : nodes) {
            addNodeTextRecursive(node, pages);
        }
    }

    private void addNodeTextRecursive(PageNode node, List<PageData> pages) {
        if (node.isLeaf()) {
            StringBuilder sb = new StringBuilder();
            for (int i = node.getStartIndex(); i <= node.getEndIndex() && i < pages.size(); i++) {
                sb.append(pages.get(i).getText()).append("\n");
            }
            node.setText(sb.toString().trim());
        }
        if (node.getNodes() != null) {
            for (PageNode child : node.getNodes()) {
                addNodeTextRecursive(child, pages);
            }
        }
    }

    /**
     * Remove text field from all nodes.
     * Mirrors remove_structure_text().
     */
    public void removeNodeText(List<PageNode> nodes) {
        for (PageNode node : nodes) {
            removeNodeTextRecursive(node);
        }
    }

    private void removeNodeTextRecursive(PageNode node) {
        node.setText(null);
        if (node.getNodes() != null) {
            for (PageNode child : node.getNodes()) {
                removeNodeTextRecursive(child);
            }
        }
    }

    /**
     * Generate LLM summaries for all nodes concurrently.
     * Mirrors generate_summaries_for_structure().
     */
    public void generateSummaries(List<PageNode> nodes, List<PageData> pages) {
        List<PageNode> allNodes = collectAllNodes(nodes);
        List<Supplier<CompletableFuture<String>>> tasks = new ArrayList<>();

        for (PageNode node : allNodes) {
            tasks.add(() -> {
                String content = getNodeContent(node, pages);
                String prompt = String.format(SUMMARY_PROMPT, node.getTitle(), content);
                return llm.callAsync(prompt);
            });
        }

        List<String> summaries = llm.gatherAsync(tasks);
        for (int i = 0; i < allNodes.size() && i < summaries.size(); i++) {
            if (summaries.get(i) != null) {
                allNodes.get(i).setSummary(summaries.get(i).trim());
            }
        }
        log.debug("Generated summaries for {} nodes", allNodes.size());
    }

    /**
     * Generate a one-line description of the whole document.
     * Mirrors generate_doc_description().
     */
    public String generateDocDescription(List<PageNode> structure) {
        String structureText = formatStructure(structure);
        String prompt = String.format(DESCRIPTION_PROMPT, structureText);
        return llm.call(prompt).getContent().trim();
    }

    /** Serialize structure titles for the description prompt. */
    private String formatStructure(List<PageNode> nodes) {
        StringBuilder sb = new StringBuilder();
        formatStructureRecursive(nodes, sb, 0);
        return sb.toString();
    }

    private void formatStructureRecursive(List<PageNode> nodes, StringBuilder sb, int depth) {
        if (nodes == null) return;
        String indent = "  ".repeat(depth);
        for (PageNode node : nodes) {
            sb.append(indent).append("- ").append(node.getTitle());
            if (node.getSummary() != null) sb.append(": ").append(node.getSummary());
            sb.append("\n");
            formatStructureRecursive(node.getNodes(), sb, depth + 1);
        }
    }

    /** Collect all nodes depth-first. */
    private List<PageNode> collectAllNodes(List<PageNode> roots) {
        List<PageNode> all = new ArrayList<>();
        if (roots == null) return all;
        for (PageNode node : roots) {
            all.add(node);
            all.addAll(collectAllNodes(node.getNodes()));
        }
        return all;
    }

    private String getNodeContent(PageNode node, List<PageData> pages) {
        if (node.getText() != null) return node.getText();
        StringBuilder sb = new StringBuilder();
        for (int i = node.getStartIndex(); i <= node.getEndIndex() && i < pages.size(); i++) {
            String text = pages.get(i).getText();
            if (text != null) sb.append(text).append("\n");
            if (sb.length() > 3000) break; // cap for summary prompt
        }
        return sb.toString().trim();
    }
}
