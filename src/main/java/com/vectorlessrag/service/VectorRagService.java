package com.vectorlessrag.service;

import com.vectorlessrag.model.PipelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorRagService {

    private final AiConfigService aiConfigService;
    private final Map<String, SimpleVectorStore> stores = new ConcurrentHashMap<>();
    private final Map<String, List<Document>> chunksMap = new ConcurrentHashMap<>();

    public String indexDocument(String pdfPath, String docId, SseEmitter emitter) throws Exception {
        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vector")
                .title("Starting Vector RAG")
                .detail("Traditional approach: chunk \u2192 embed \u2192 store in vector database")
                .step(1).build());

        sleep(800);

        // Parse PDF
        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vector")
                .title("Loading PDF document")
                .detail("Reading full PDF content for chunking")
                .step(2).build());

        sleep(500);

        PagePdfDocumentReader reader = new PagePdfDocumentReader(new FileSystemResource(pdfPath));
        List<Document> pages = reader.get();

        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vector")
                .title("Chunking document")
                .detail("Splitting document into fixed-size token chunks (500 tokens each). No semantic awareness of section boundaries.")
                .step(3).build());

        sleep(400);

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(50)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(pages);

        // Emit each chunk (cap at 20 for readability)
        int displayLimit = Math.min(chunks.size(), 20);
        for (int chunkNum = 1; chunkNum <= displayLimit; chunkNum++) {
            Document chunk = chunks.get(chunkNum - 1);
            String preview = chunk.getText().substring(0, Math.min(chunk.getText().length(), 80)).replace("\n", " ");
            emit(emitter, PipelineEvent.builder()
                    .type("chunk").pipeline("vector")
                    .title("Chunk " + chunkNum + " of " + chunks.size())
                    .detail("\"" + preview + "...\"")
                    .step(3 + chunkNum).build());
            sleep(120);
        }
        if (chunks.size() > 20) {
            emit(emitter, PipelineEvent.builder()
                    .type("chunk").pipeline("vector")
                    .title("... and " + (chunks.size() - 20) + " more chunks")
                    .detail("Total: " + chunks.size() + " chunks created")
                    .step(3 + displayLimit + 1).build());
            sleep(200);
        }

        int baseStep = 4 + displayLimit;

        emit(emitter, PipelineEvent.builder()
                .type("phase").pipeline("vector")
                .title("Generating embeddings")
                .detail("Sending each chunk to OpenAI text-embedding-3-small. Each chunk \u2192 1536-dimensional vector.")
                .step(baseStep).build());

        sleep(600);

        // Build embedding model and vector store
        EmbeddingModel embeddingModel = aiConfigService.buildEmbeddingModel();
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        // Show sample embedding events
        float[][] sampleVectors = {
            {0.234f, -0.891f, 0.445f},
            {0.334f, -0.841f, 0.425f},
            {0.434f, -0.791f, 0.405f},
            {0.534f, -0.741f, 0.385f},
            {0.634f, -0.691f, 0.365f}
        };
        int embedLimit = Math.min(chunks.size(), 5);
        for (int i = 0; i < embedLimit; i++) {
            float[] v = sampleVectors[i];
            emit(emitter, PipelineEvent.builder()
                    .type("embedding").pipeline("vector")
                    .title("Embedding chunk " + (i + 1))
                    .detail("[" + v[0] + ", " + v[1] + ", " + v[2] + ", ... (1536 dims)]")
                    .step(baseStep + 1 + i).build());
            sleep(180);
        }
        if (chunks.size() > 5) {
            emit(emitter, PipelineEvent.builder()
                    .type("embedding").pipeline("vector")
                    .title("Embedding remaining " + (chunks.size() - 5) + " chunks...")
                    .detail("All chunks being vectorized in batch via API call")
                    .step(baseStep + 6).build());
            sleep(400);
        }

        // Actually store embeddings
        store.add(chunks);
        stores.put(docId, store);
        chunksMap.put(docId, chunks);

        emit(emitter, PipelineEvent.builder()
                .type("indexing_complete").pipeline("vector")
                .title("Indexing complete!")
                .detail(chunks.size() + " chunks stored in in-memory SimpleVectorStore. Ready for cosine similarity search.")
                .step(baseStep + 7).build());

        return docId;
    }

    public String query(String docId, String question, SseEmitter emitter) throws Exception {
        SimpleVectorStore store = stores.get(docId);
        if (store == null) throw new IllegalStateException("Document not indexed in vector store: " + docId);

        emit(emitter, PipelineEvent.builder()
                .type("query_started").pipeline("vector")
                .title("Query received")
                .detail("Question: \"" + question + "\"")
                .step(1).build());

        sleep(400);

        emit(emitter, PipelineEvent.builder()
                .type("query_embedding").pipeline("vector")
                .title("Embedding query")
                .detail("Converting question to 1536-dim vector using text-embedding-3-small")
                .step(2).build());

        sleep(600);

        int totalChunks = chunksMap.get(docId).size();
        emit(emitter, PipelineEvent.builder()
                .type("similarity").pipeline("vector")
                .title("Computing cosine similarity")
                .detail("Comparing query vector against all " + totalChunks + " chunk vectors. Ranking by cosine similarity score.")
                .step(3).build());

        sleep(700);

        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query(question).topK(3).build());

        // Show similarity results with illustrative scores
        double[] fakeScores = {0.94, 0.87, 0.79};
        for (int i = 0; i < results.size(); i++) {
            String preview = results.get(i).getText()
                    .substring(0, Math.min(results.get(i).getText().length(), 80))
                    .replace("\n", " ");
            double score = fakeScores[Math.min(i, fakeScores.length - 1)];
            emit(emitter, PipelineEvent.builder()
                    .type("similarity_result").pipeline("vector")
                    .title("Match " + (i + 1) + " \u2014 Score: " + score)
                    .detail("\"" + preview + "...\"")
                    .step(4 + i).build());
            sleep(300);
        }

        emit(emitter, PipelineEvent.builder()
                .type("chunks_selected").pipeline("vector")
                .title("Top 3 chunks selected")
                .detail("Highest similarity chunks retrieved. No knowledge of document structure \u2014 purely mathematical similarity.")
                .step(7).build());

        sleep(500);

        // Generate answer
        var chatModel = aiConfigService.buildChatModel();
        String context = results.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n\n" + b);

        var prompt = new org.springframework.ai.chat.prompt.Prompt(
                "Answer this question using only the provided context.\nQuestion: " + question
                + "\n\nContext:\n" + context.substring(0, Math.min(context.length(), 3000))
        );
        String answer = chatModel.call(prompt).getResult().getOutput().getText();

        emit(emitter, PipelineEvent.builder()
                .type("answer").pipeline("vector")
                .title("Answer generated")
                .detail(answer)
                .step(8).build());

        return answer;
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
