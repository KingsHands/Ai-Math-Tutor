package com.baezcostiganreed.mathtutorapp;

import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
public class ChatController {
    private static final int TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = .6;
    private static final int MAX_HISTORY = 10;

    private final OllamaApi ollamaApi = new OllamaApi("http://localhost:11434");
    private final PgVectorStore vectorStore;

    private static final Map<String, Deque<OllamaApi.Message>> sessionHistory = new ConcurrentHashMap<>();
    private final Map<String, List<Document>> vectorCache = new ConcurrentHashMap<>();

    @Value("classpath:/prompts/prompt_template.txt")
    private Resource systemTemplateResource;

    private String systemPromptTemplate;

    public ChatController(PgVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void loadSystemPrompt() throws IOException {
        this.systemPromptTemplate = systemTemplateResource.getContentAsString(Charset.defaultCharset());
    }

    @GetMapping("/chat")
    public Flux<OllamaApi.ChatResponse> chat(
            @RequestParam(value = "topic", defaultValue = "") String topic,
            @RequestParam(value = "usermessage", defaultValue = " ") String usermessage,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId
    ) {
        if (topic == null || topic.trim().isEmpty()) {
            return Flux.error(new IllegalArgumentException("Please select a topic"));
        }

        String filterExpressionChapter = switch (topic) {
            case "linear equations" ->
                    "file_name == 'fundamentals-of-mathematics.pdf' && page_number >= 1249 && page_number <= 1357 || file_name == 'Beginning_and_Intermediate_Algebra.pdf' && page_number >= 28 && page_number <= 79";
            case "fractions" ->
                    "file_name == 'fundamentals-of-mathematics.pdf' && page_number >= 400 && page_number <= 645 || file_name == 'Beginning_and_Intermediate_Algebra.pdf' && page_number >= 12 && page_number <= 17";
            case "integers" ->
                    "file_name == 'fundamentals-of-mathematics.pdf' && page_number >= 15 && page_number <= 268 || file_name == 'Beginning_and_Intermediate_Algebra.pdf' && page_number >= 7 && page_number <= 9";
            case "real numbers" ->
                    "file_name == 'fundamentals-of-mathematics.pdf' && page_number >= 1131 && page_number <= 1144";
            case "signed numbers" ->
                    "file_name == 'fundamentals-of-mathematics.pdf' && page_number >= 1145 && page_number <= 1220";
            case "decimals" ->
                    "file_name == 'fundamentals-of-mathematics.pdf' && page_number >= 663 && page_number <= 828";
            case "percents" ->
                    "file_name == 'fundamentals-of-mathematics.pdf' && page_number >= 848 && page_number <= 920";
            case "polynomials" ->
                    "file_name == 'Beginning_and_Intermediate_Algebra.pdf' && page_number >= 177 && page_number <= 205";
            case "factoring" ->
                    "file_name == 'Beginning_and_Intermediate_Algebra.pdf' && page_number >= 212 && page_number <= 237";
            default -> "";
        };

        String cacheKey = String.join("::", topic.trim(), usermessage.trim()).toLowerCase();
        List<Document> chapterResults = vectorCache.computeIfAbsent(cacheKey, key ->
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query("Steps to find the solution. How to Solve. " + topic + " " + usermessage)
                                .topK(TOP_K)
                                .similarityThreshold(SIMILARITY_THRESHOLD)
                                .filterExpression(filterExpressionChapter)
                                .build()));

        String chapterContent = chapterResults != null && !chapterResults.isEmpty()
                ? chapterResults.stream().map(Document::getText).collect(Collectors.joining("\n "))
                : "No documents provided\n";

        String systemPrompt = String.format(systemPromptTemplate, topic);

        Deque<OllamaApi.Message> history = sessionHistory.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        history.add(OllamaApi.Message.builder(OllamaApi.Message.Role.USER).content(usermessage).build());
        history.add(OllamaApi.Message.builder(OllamaApi.Message.Role.TOOL).content(chapterContent).build());

        while (history.size() > MAX_HISTORY * 2) {
            history.pollFirst();
        }

        List<OllamaApi.Message> messages = new ArrayList<>();
        messages.add(OllamaApi.Message.builder(OllamaApi.Message.Role.SYSTEM).content(systemPrompt).build());
        messages.addAll(history);

        OllamaApi.ChatRequest request = OllamaApi.ChatRequest.builder("phi4-mini")
                .stream(true)
                .messages(messages)
                .options(OllamaOptions.builder()
                        .numCtx(4000)
                        .temperature(.2)
                        .topP(.4)
                        .build())
                .build();

        return this.ollamaApi.streamingChat(request);
    }
}
