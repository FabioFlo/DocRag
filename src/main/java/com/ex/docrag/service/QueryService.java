package com.ex.docrag.service;

import com.ex.docrag.config.DocRagProperties;
import com.ex.docrag.model.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Handles the RAG query pipeline:
 *  1. Embed the user's question (done implicitly by VectorStore.similaritySearch)
 *  2. Retrieve top-K relevant chunks from Qdrant
 *  3. Build a RAG prompt (context chunks + question)
 *  4. Send to Ollama for answer generation
 *  5. Return answer + source excerpts
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final DocRagProperties props;

    private static final String SYSTEM_PROMPT = """
            You are a helpful document assistant. Your job is to answer questions
            based ONLY on the context provided below. Do not use any outside knowledge.
            If the answer is not found in the context, say "I could not find an answer
            in the available documents."

            Always be concise and cite which document the information comes from when possible.
            """;

    public QueryService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                        DocRagProperties props) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.props = props;
    }

    /**
     * Main query method.
     *
     * @param question  the user's natural-language question
     * @param argument  optional topic filter — if provided, only chunks from that folder are searched
     * @return QueryResponse with the LLM answer and the source chunks used
     */
    public QueryResponse query(String question, String argument) {
        log.info("Query received — question: '{}', argument filter: '{}'", question, argument);

        // 1. Build the similarity search request
        SearchRequest searchRequest = buildSearchRequest(question, argument);

        // 2. Execute semantic search in Qdrant
        log.debug("Executing similarity search in Qdrant (topK={})...", props.getTopK());
        List<Document> relevantChunks = vectorStore.similaritySearch(searchRequest);
        log.info("Retrieved {} relevant chunk(s) from Qdrant", relevantChunks.size());

        if (relevantChunks.isEmpty()) {
            log.warn("No relevant chunks found for question: '{}'", question);
            return new QueryResponse(
                    "I could not find any relevant documents to answer your question. " +
                    "Please make sure documents have been uploaded and indexed.",
                    List.of(),
                    argument
            );
        }

        // 3. Log what we found
        for (int i = 0; i < relevantChunks.size(); i++) {
            Document chunk = relevantChunks.get(i);
            log.debug("Chunk [{}] — source: {}, argument: {}, preview: '{}'",
                    i + 1,
                    chunk.getMetadata().get("source_file"),
                    chunk.getMetadata().get("argument"),
                    truncate(chunk.getText(), 120));
        }

        // 4. Build context string from retrieved chunks
        String context = buildContext(relevantChunks);

        // 5. Build the full user message with context + question
        String userMessage = """
                CONTEXT FROM DOCUMENTS:
                ---
                %s
                ---

                QUESTION: %s
                """.formatted(context, question);

        log.debug("Sending prompt to Ollama (context length: {} chars)...", context.length());

        // 6. Call the LLM
        String answer;
        try {
            answer = chatClient
                    .prompt()
                    .user(userMessage)
                    .call()
                    .content();
            log.info("LLM response received (length: {} chars)", answer != null ? answer.length() : 0);
        } catch (Exception e) {
            log.error("Error calling Ollama LLM: {}", e.getMessage(), e);
            answer = "An error occurred while generating the answer. " +
                     "Please check that Ollama is running and the model is available. Error: " + e.getMessage();
        }

        // 7. Collect source excerpt strings for display in the UI
        List<String> sourceChunks = relevantChunks.stream()
                .map(d -> "[%s] %s".formatted(
                        d.getMetadata().getOrDefault("source_file", "unknown"),
                        truncate(d.getText(), 300)
                ))
                .toList();

        return new QueryResponse(answer, sourceChunks, argument);
    }

    private SearchRequest buildSearchRequest(String question, String argument) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(question)
                .topK(props.getTopK());

        if (StringUtils.hasText(argument)) {
            log.debug("Applying argument filter: '{}'", argument);
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            builder.filterExpression(b.eq("argument", argument).build());
        }

        return builder.build();
    }

    private String buildContext(List<Document> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String sourceFile = (String) chunk.getMetadata().getOrDefault("source_file", "unknown");
            sb.append("[Source %d — %s]\n".formatted(i + 1, sourceFile));
            sb.append(chunk.getText()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
