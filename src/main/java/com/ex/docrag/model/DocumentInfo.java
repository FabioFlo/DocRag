package com.ex.docrag.model;

/**
 * Lightweight DTO representing a single indexed document.
 * Used by the REST API and Thymeleaf UI to list what has been ingested.
 */
public class DocumentInfo {

    private String fileName;
    private String argument;    // the parent folder name = the topic
    private String filePath;
    private long fileSizeBytes;

    public DocumentInfo() {}

    public DocumentInfo(String fileName, String argument, String filePath, long fileSizeBytes) {
        this.fileName = fileName;
        this.argument = argument;
        this.filePath = filePath;
        this.fileSizeBytes = fileSizeBytes;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getArgument() { return argument; }
    public void setArgument(String argument) { this.argument = argument; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
}
