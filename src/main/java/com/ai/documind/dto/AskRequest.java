package com.ai.documind.dto;

public class AskRequest {
    private String question;
    private String sessionId;

    public AskRequest() {}

    public AskRequest(String question, String sessionId) {
        this.question = question;
        this.sessionId = sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
