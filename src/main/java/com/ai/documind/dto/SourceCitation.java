package com.ai.documind.dto;

public class SourceCitation {
    private String fileName;
    private Double score;
    private String text;

    public SourceCitation() {}

    public SourceCitation(String fileName, Double score, String text) {
        this.fileName = fileName != null ? fileName : "Direct Ingestion / Unknown";
        this.score = score != null ? Math.round(score * 1000.0) / 1000.0 : null;
        this.text = text;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
