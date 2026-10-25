package com.campus.share.dto;

public record ItemQuery(String scope, String q, Long categoryId, String campus,
                        boolean availableOnly, String sort, int page, int size) {
    public int getOffset() { return (page - 1) * size; }
}
