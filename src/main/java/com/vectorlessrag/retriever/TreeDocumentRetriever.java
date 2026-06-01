package com.vectorlessrag.retriever;

import com.vectorlessrag.tree.TreeNode;
import com.vectorlessrag.tree.TreeStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TreeDocumentRetriever implements DocumentRetriever {

  private final ChatClient chatClient;
  private final TreeStore treeStore;
  private final Map<String, List<String>> pageContentsCache = new ConcurrentHashMap<>();
  private static final int MAX_DEPTH = 3;
  private String activeDocumentId;

  /**
   * Called by Spring AI RetrievalAugmentationAdvisor. Query comes in → returns relevant
   * List<Document>. documentId must be set via activeDocument() before querying.
   */
  @Override
  public List<Document> retrieve(Query query) {
    String queryText = query.text();
    log.info("TreeRetriever — query: {}", queryText);

    if (activeDocumentId == null) {
      log.warn("No active document set. Call activeDocument(docId) first.");
      return List.of();
    }

    Optional<TreeNode> rootOpt = treeStore.get(activeDocumentId);
    if (rootOpt.isEmpty()) {
      log.warn("No tree found for document: {}", activeDocumentId);
      return List.of();
    }

    List<String> pages = pageContentsCache.get(activeDocumentId);
    if (pages == null) {
      log.warn("No page contents cached for: {}", activeDocumentId);
      return List.of();
    }

    // Navigate tree — core algorithm
    TreeNode selectedNode = navigateTree(rootOpt.get(), queryText, 0);
    log.info(
        "Selected node: '{}' (pages {}-{})",
        selectedNode.getTitle(),
        selectedNode.getStartIndex(),
        selectedNode.getEndIndex());

    // Fetch pages for selected node
    return fetchPages(selectedNode, pages);
  }

  /**
   * Recursive tree navigation — PageIndex core algorithm. LLM picks which child to go into at each
   * level.
   */
  private TreeNode navigateTree(TreeNode node, String query, int depth) {
    // Base cases
    if (node.isLeaf() || depth >= MAX_DEPTH) {
      return node;
    }

    // Ask LLM which child node is most relevant
    String chosenNodeId = askLlmToPickNode(node.getChildren(), query);

    // Find chosen child
    Optional<TreeNode> chosenChild =
        node.getChildren().stream()
            .filter(child -> child.getNodeId().equals(chosenNodeId))
            .findFirst();

    if (chosenChild.isEmpty()) {
      log.warn("LLM picked unknown nodeId: {}. Staying at current node.", chosenNodeId);
      return node;
    }

    log.info("Depth {}: navigating into '{}'", depth, chosenChild.get().getTitle());

    // Recurse deeper
    return navigateTree(chosenChild.get(), query, depth + 1);
  }

  /** Core LLM call — given list of sibling nodes + query, LLM picks which node_id to go into. */
  private String askLlmToPickNode(List<TreeNode> siblings, String query) {
    StringBuilder nodesContext = new StringBuilder();
    for (TreeNode node : siblings) {
      nodesContext.append(
          String.format(
              "node_id: %s | title: %s | summary: %s | pages: %d-%d\n",
              node.getNodeId(),
              node.getTitle(),
              node.getSummary(),
              node.getStartIndex(),
              node.getEndIndex()));
    }

    String prompt =
        """
                You are a document navigator. Given the user query and available sections,
                pick the SINGLE most relevant section to find the answer.

                User query: %s

                Available sections:
                %s

                Respond with ONLY the node_id value. Nothing else. No explanation.
                Example response: 0003
                """
            .formatted(query, nodesContext);

    String response =
        chatClient
            .prompt()
            .user(prompt)
            .call()
            .content()
            .trim()
            .replaceAll("[^0-9]", ""); // extract only digits

    log.info("LLM picked node_id: {}", response);
    return response;
  }

  /** Fetch actual page content for a node's page range. Returns as Spring AI Document objects. */
  private List<Document> fetchPages(TreeNode node, List<String> allPages) {
    List<Document> docs = new ArrayList<>();

    int start = Math.max(0, node.getStartIndex());
    int end = Math.min(allPages.size(), node.getEndIndex());

    for (int i = start; i < end; i++) {
      String content = allPages.get(i);
      if (content != null && !content.isBlank()) {
        docs.add(
            new Document(
                content,
                Map.of(
                    "page",
                    i,
                    "node_id",
                    node.getNodeId(),
                    "node_title",
                    node.getTitle(),
                    "document_id",
                    activeDocumentId)));
      }
    }

    log.info("Fetched {} pages from node '{}'", docs.size(), node.getTitle());
    return docs;
  }

  public void loadDocument(String documentId, List<String> pageContents) {
    this.activeDocumentId = documentId;
    this.pageContentsCache.put(documentId, pageContents);
    log.info("Active document set to: {}", documentId);
  }

  public void activeDocument(String documentId) {
    this.activeDocumentId = documentId;
  }
}
