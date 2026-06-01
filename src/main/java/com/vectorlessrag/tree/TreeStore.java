package com.vectorlessrag.tree;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TreeStore {

  private final ObjectMapper objectMapper;

  // in-memory store — key = document name, value = root TreeNode
  private final Map<String, TreeNode> treeCache = new ConcurrentHashMap<>();

  private static final String STORE_DIR = "tree-store/";

  /** Save tree — both in memory and to disk. */
  public void save(String documentId, TreeNode root) {
    treeCache.put(documentId, root);
    persistToDisk(documentId, root);
    log.info("Tree saved for document: {}", documentId);
  }

  /** Get tree — from memory first, then disk. */
  public Optional<TreeNode> get(String documentId) {
    if (treeCache.containsKey(documentId)) {
      log.info("Tree found in memory for: {}", documentId);
      return Optional.of(treeCache.get(documentId));
    }
    return loadFromDisk(documentId);
  }

  /** Check if tree already exists — avoids rebuilding. */
  public boolean exists(String documentId) {
    return treeCache.containsKey(documentId) || new File(STORE_DIR + documentId + ".json").exists();
  }

  /** Delete tree (memory + disk). */
  public void delete(String documentId) {
    treeCache.remove(documentId);
    File file = new File(STORE_DIR + documentId + ".json");
    if (file.exists()) file.delete();
    log.info("Tree deleted for: {}", documentId);
  }

  // --- private ---
  private void persistToDisk(String documentId, TreeNode root) {
    try {
      File dir = new File(STORE_DIR);
      if (!dir.exists()) dir.mkdirs();

      File file = new File(STORE_DIR + documentId + ".json");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
      log.info("Tree persisted to: {}", file.getAbsolutePath());

    } catch (IOException e) {
      log.error("Failed to persist tree for {}: {}", documentId, e.getMessage());
    }
  }

  private Optional<TreeNode> loadFromDisk(String documentId) {
    File file = new File(STORE_DIR + documentId + ".json");
    if (!file.exists()) {
      log.info("No tree found on disk for: {}", documentId);
      return Optional.empty();
    }
    try {
      TreeNode root = objectMapper.readValue(file, TreeNode.class);
      treeCache.put(documentId, root); // warm up memory cache
      log.info("Tree loaded from disk for: {}", documentId);
      return Optional.of(root);
    } catch (IOException e) {
      log.error("Failed to load tree for {}: {}", documentId, e.getMessage());
      return Optional.empty();
    }
  }
}
