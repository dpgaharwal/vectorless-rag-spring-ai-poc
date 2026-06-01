package com.vectorlessrag.tree;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TreeBuilder {

  private final ChatClient chatClient;
  private final ObjectMapper objectMapper;

  private static final int MAX_PAGES_PER_NODE = 10;
  private static final int TOC_CHECK_PAGES = 5;

  /**
   * Entry point — takes extracted PDF pages, returns root TreeNode. Each string in pageContents =
   * one page's text.
   */
  public TreeNode build(List<String> pageContents) {
    log.info("Building tree for {} pages", pageContents.size());
    List<String> pageSummaries = summarizePages(pageContents);
    // pass actual size, not limited size
    String treeJson = callLlmForTree(pageSummaries, pageContents.size());
    return parseTreeJson(treeJson, pageContents.size());
  }

  /**
   * Summarize each page in one line — LLM uses these to build tree. Avoids sending full page
   * content to tree-building prompt.
   */
  private List<String> summarizePages(List<String> pageContents) {
    AtomicInteger pageNum = new AtomicInteger(0);

    // Limit to first 50 pages for tree building
    return pageContents.stream()
        .limit(20)
        .map(
            content -> {
              int page = pageNum.getAndIncrement();
              if (content == null || content.isBlank()) {
                return "Page " + page + ": [empty]";
              }
              // Reduce to 200 chars
              String truncated =
                  content.length() > 100 ? content.substring(0, 200) + "..." : content;
              return "Page " + page + ": " + truncated;
            })
        .toList();
  }

  /** Core LLM call — sends page summaries, gets back tree JSON. */
  private String callLlmForTree(List<String> pageSummaries, int totalPages) {
    String pagesContext = String.join("\n---\n", pageSummaries);

    String prompt =
        """
                You are a document indexer. Analyze the following page summaries and build a hierarchical tree structure.

                Rules:
                1. Group related pages into logical sections (chapters, topics, subsections).
                2. Each node must have a clear title representing that section.
                3. Write a 1-sentence summary for each node.
                4. start_index and end_index are page numbers (0-based, end is exclusive).
                5. Max %d pages per leaf node.
                6. Return ONLY valid JSON, no explanation, no markdown.

                JSON format:
                {
                  "node_id": "0001",
                  "title": "Document Root",
                  "summary": "Overall document summary",
                  "start_index": 0,
                  "end_index": %d,
                  "nodes": [
                    {
                      "node_id": "0002",
                      "title": "Section Title",
                      "summary": "What this section covers",
                      "start_index": 0,
                      "end_index": 5,
                      "nodes": []
                    }
                  ]
                }

                Page summaries:
                %s
                """
            .formatted(MAX_PAGES_PER_NODE, totalPages, pagesContext);

    log.info("Calling LLM to build tree structure...");

    return chatClient.prompt().user(prompt).call().content();
  }

  /** Parse LLM JSON response → TreeNode. LLM sometimes wraps in markdown — strip that first. */
  private TreeNode parseTreeJson(String rawJson, int totalPages) {
    try {
      // Strip markdown code blocks if LLM adds them
      String cleaned = rawJson.replaceAll("```json", "").replaceAll("```", "").trim();

      TreeNode root = objectMapper.readValue(cleaned, TreeNode.class);
      log.info(
          "Tree built successfully. Root: '{}', children: {}",
          root.getTitle(),
          root.getChildren().size());
      return root;

    } catch (Exception e) {
      log.error("Failed to parse tree JSON: {}", e.getMessage());
      log.debug("Raw LLM response: {}", rawJson);
      // Fallback — flat root with no children
      return TreeNode.builder()
          .nodeId("0001")
          .title("Document Root")
          .summary("Full document")
          .startIndex(0)
          .endIndex(totalPages)
          .build();
    }
  }
}
