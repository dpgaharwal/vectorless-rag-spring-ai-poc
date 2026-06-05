package io.github.dpgaharwal.pageindex.tree;

import io.github.dpgaharwal.pageindex.config.PageIndexConfig;
import io.github.dpgaharwal.pageindex.model.PageNode;
import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.model.TocMode;
import io.github.dpgaharwal.pageindex.pdf.PageData;
import io.github.dpgaharwal.pageindex.toc.TocMetaProcessor;
import io.github.dpgaharwal.pageindex.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Recursively splits nodes that exceed max pages or token limits.
 * Mirrors process_large_node_recursively() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeSplitter {

    private final TocMetaProcessor metaProcessor;
    private final TreeAssembler assembler;
    private final TokenCounter tokenCounter;

    /**
     * Recursively check if a node is too large; if so, re-run NO_TOC processing on its pages
     * and graft results as children.
     * Mirrors process_large_node_recursively().
     */
    public PageNode splitLargeNodeRecursively(PageNode node, List<PageData> allPages,
                                               PageIndexConfig config) {
        int pageCount = node.getEndIndex() - node.getStartIndex() + 1;

        // Calculate token count for this node's pages
        int tokenCount = 0;
        for (int i = node.getStartIndex(); i <= node.getEndIndex() && i < allPages.size(); i++) {
            tokenCount += allPages.get(i).getTokenCount() > 0
                    ? allPages.get(i).getTokenCount()
                    : tokenCounter.count(allPages.get(i).getText());
        }

        boolean tooLarge = pageCount > config.getMaxPageNumEachNode()
                || tokenCount > config.getMaxTokenNumEachNode();

        if (!tooLarge || !node.isLeaf()) {
            // Recursively process existing children
            if (node.getNodes() != null && !node.getNodes().isEmpty()) {
                List<PageNode> processedChildren = node.getNodes().stream()
                        .map(child -> splitLargeNodeRecursively(child, allPages, config))
                        .collect(Collectors.toList());
                node.setNodes(processedChildren);
            }
            return node;
        }

        log.debug("Splitting large node '{}' ({} pages, {} tokens)", node.getTitle(), pageCount, tokenCount);

        // Extract the sub-page list for this node
        List<PageData> subPages = allPages.subList(
                Math.min(node.getStartIndex(), allPages.size()),
                Math.min(node.getEndIndex() + 1, allPages.size()));

        try {
            List<TocItem> subItems = metaProcessor.process(subPages, TocMode.NO_TOC,
                    null, List.of(), 0, config);

            // Adjust physical indices relative to node.startIndex
            List<TocItem> adjusted = subItems.stream().map(item -> {
                if (item.getPhysicalIndex() == null) return item;
                return TocItem.builder()
                        .structure(item.getStructure()).title(item.getTitle())
                        .physicalIndex(item.getPhysicalIndex() + node.getStartIndex())
                        .appearStart(item.getAppearStart()).build();
            }).collect(Collectors.toList());

            List<PageNode> children = assembler.postProcess(adjusted, node.getEndIndex());
            if (!children.isEmpty()) {
                node.setNodes(children);
                log.debug("Split '{}' into {} children", node.getTitle(), children.size());
            }
        } catch (Exception e) {
            log.warn("Could not split large node '{}': {}", node.getTitle(), e.getMessage());
        }

        return node;
    }

    /**
     * Process all root nodes, splitting large ones concurrently.
     */
    public List<PageNode> splitAllLargeNodes(List<PageNode> roots, List<PageData> allPages,
                                              PageIndexConfig config) {
        List<CompletableFuture<PageNode>> futures = roots.stream()
                .map(root -> CompletableFuture.supplyAsync(
                        () -> splitLargeNodeRecursively(root, allPages, config)))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
                .map(f -> {
                    try { return f.join(); }
                    catch (Exception e) {
                        log.error("Node splitting failed", e);
                        return null;
                    }
                })
                .filter(n -> n != null)
                .collect(Collectors.toList());
    }
}
