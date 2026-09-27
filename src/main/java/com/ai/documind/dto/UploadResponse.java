package com.ai.documind.dto;

public class UploadResponse {
    private String fileName;
    private long fileSize;
    private int chunksCount;
    private String message;
    private boolean success;

    public UploadResponse() {}

    public UploadResponse(String fileName, long fileSize, int chunksCount, String message, boolean success) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.chunksCount = chunksCount;
        this.message = message;
        this.success = success;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public int getChunksCount() {
        return chunksCount;
    }

    public void setChunksCount(int chunksCount) {
        this.chunksCount = chunksCount;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }
}
