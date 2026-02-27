package com.ex.docrag.model;

import java.util.List;

/**
 * Response DTO for a query.
 * Contains the LLM-generated answer and the source chunk excerpts used to generate it.
 */
public class QueryResponse {

    private String answer;
    private List<String> sourceChunks;   // top-K raw text snippets shown to user
    private String argumentFilter;       // echoes back which filter was applied (if any)

    public QueryResponse(String answer, List<String> sourceChunks, String argumentFilter) {
        this.answer = answer;
        this.sourceChunks = sourceChunks;
        this.argumentFilter = argumentFilter;
    }

    public String getAnswer() { return answer; }
    public List<String> getSourceChunks() { return sourceChunks; }
    public String getArgumentFilter() { return argumentFilter; }
}
