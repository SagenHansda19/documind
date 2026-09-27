package com.ai.documind.dto;

import java.util.List;

public class AskResponse {
    private String answer;
    private String sessionId;
    private List<SourceCitation> sources;

    public AskResponse() {}

    public AskResponse(String answer, String sessionId, List<SourceCitation> sources) {
        this.answer = answer;
        this.sessionId = sessionId;
        this.sources = sources;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<SourceCitation> getSources() {
        return sources;
    }

    public void setSources(List<SourceCitation> sources) {
        this.sources = sources;
    }
}
