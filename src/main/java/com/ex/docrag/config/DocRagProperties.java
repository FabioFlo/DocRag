package com.ex.docrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the custom 'docrag.*' properties from application.yml.
 * Spring Boot autopopulates these fields on startup.
 */
@Component
@ConfigurationProperties(prefix = "docrag")
public class DocRagProperties {

    /** Absolute path to the root documents folder (contains topic sub-folders). */
    private String documentsPath;

    /** Max tokens per chunk when splitting documents. */
    private int chunkSize = 500;

    /** Overlap in tokens between adjacent chunks (helps context continuity). */
    private int chunkOverlap = 100;

    /** Number of top similar chunks to retrieve from Qdrant for each query. */
    private int topK = 5;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getDocumentsPath() { return documentsPath; }
    public void setDocumentsPath(String documentsPath) { this.documentsPath = documentsPath; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
}
