package com.campus.share.entity;

import java.time.LocalDateTime;

public class ItemEntity {
    private Long id;
    private Long ownerId;
    private Long categoryId;
    private String title;
    private String description;
    private String campus;
    private String pickupLocation;
    private LocalDateTime now;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCampus() { return campus; }
    public void setCampus(String campus) { this.campus = campus; }
    public String getPickupLocation() { return pickupLocation; }
    public void setPickupLocation(String pickupLocation) { this.pickupLocation = pickupLocation; }
    public LocalDateTime getNow() { return now; }
    public void setNow(LocalDateTime now) { this.now = now; }
}
