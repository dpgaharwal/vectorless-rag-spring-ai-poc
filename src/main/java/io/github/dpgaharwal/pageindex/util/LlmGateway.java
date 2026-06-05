package io.github.dpgaharwal.pageindex.util;

import io.github.dpgaharwal.pageindex.model.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Single bridge between PageIndex and Spring AI's ChatModel.
 * Handles retries, async fan-out, and multi-turn chat history.
 * Mirrors llm_completion() and llm_acompletion() from utils.py.
 */
@Slf4j
@Component
public class LlmGateway {

    private final ChatModel chatModel;
    private final Executor executor;
    private final int maxRetries;
    private final long retryDelayMs;

    public LlmGateway(
            ChatModel chatModel,
            @Qualifier("pageIndexExecutor") Executor executor) {
        this.chatModel = chatModel;
        this.executor = executor;
        this.maxRetries = 10;
        this.retryDelayMs = 1000;
    }

    /** Synchronous call with a single user prompt. */
    public LlmResponse call(String prompt) {
        return call(List.of(new UserMessage(prompt)));
    }

    /** Synchronous call with explicit chat history (for continuation turns). */
    public LlmResponse call(List<Message> messages) {
        Exception lastException = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                ChatResponse response = chatModel.call(new Prompt(messages));
                String content = response.getResult().getOutput().getText();
                return new LlmResponse(content, LlmResponse.FinishReason.FINISHED);
            } catch (Exception e) {
                lastException = e;
                log.warn("LLM call attempt {}/{} failed: {}", attempt + 1, maxRetries, e.getMessage());
                if (attempt < maxRetries - 1) {
                    try { Thread.sleep(retryDelayMs * (attempt + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("LLM call failed after {} attempts", maxRetries, lastException);
        return new LlmResponse("", LlmResponse.FinishReason.ERROR);
    }

    /** Async call — returns CompletableFuture<String>. */
    public CompletableFuture<String> callAsync(String prompt) {
        return CompletableFuture.supplyAsync(
                () -> call(prompt).getContent(), executor);
    }

    /** Async call with history. */
    public CompletableFuture<LlmResponse> callAsyncFull(List<Message> messages) {
        return CompletableFuture.supplyAsync(() -> call(messages), executor);
    }

    /**
     * Fan-out N tasks concurrently (mirrors asyncio.gather).
     * Results are returned in the same order as the input suppliers.
     */
    public <T> List<T> gatherAsync(List<Supplier<CompletableFuture<T>>> tasks) {
        List<CompletableFuture<T>> futures = tasks.stream()
                .map(Supplier::get)
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream()
                .map(f -> {
                    try { return f.join(); }
                    catch (Exception e) {
                        log.error("Async task failed", e);
                        return null;
                    }
                })
                .toList();
    }

    /** Build a two-turn continuation prompt (initial + continue). */
    public List<Message> continuationMessages(String initialPrompt,
                                               String assistantResponse,
                                               String continuePrompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(initialPrompt));
        messages.add(new AssistantMessage(assistantResponse));
        messages.add(new UserMessage(continuePrompt));
        return messages;
    }
}
