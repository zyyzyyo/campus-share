package com.campus.share.entity;

import java.time.LocalDateTime;

public class BorrowOrderEntity {
    private Long id;
    private Long itemId;
    private Long borrowerId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime reviewExpiresAt;
    private LocalDateTime now;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Long getBorrowerId() { return borrowerId; }
    public void setBorrowerId(Long borrowerId) { this.borrowerId = borrowerId; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public LocalDateTime getReviewExpiresAt() { return reviewExpiresAt; }
    public void setReviewExpiresAt(LocalDateTime reviewExpiresAt) { this.reviewExpiresAt = reviewExpiresAt; }
    public LocalDateTime getNow() { return now; }
    public void setNow(LocalDateTime now) { this.now = now; }
}
