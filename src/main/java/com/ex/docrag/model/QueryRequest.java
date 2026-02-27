package com.ex.docrag.model;

/**
 * Incoming payload for POST /api/query.
 * 'argument' is optional — if provided, limits the search to that topic folder.
 */
public class QueryRequest {

    private String question;
    private String argument;    // optional topic filter (e.g. "tax-law")

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getArgument() { return argument; }
    public void setArgument(String argument) { this.argument = argument; }
}
