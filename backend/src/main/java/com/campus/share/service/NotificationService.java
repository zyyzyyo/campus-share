package com.campus.share.service;

import com.campus.share.common.BusinessException;
import com.campus.share.mapper.NotificationMapper;
import com.campus.share.vo.*;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private final NotificationMapper mapper;
    private final Clock clock;
    public NotificationService(NotificationMapper mapper, Clock clock) { this.mapper=mapper; this.clock=clock; }
    public PageResult<NotificationView> list(Long userId, int page, int size) {
        if (page<1 || page>100000 || size<1 || size>50)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","消息分页参数不合法");
        return new PageResult<>(mapper.list(userId,(page-1)*size,size),mapper.count(userId),page,size);
    }
    public long unread(Long userId) { return mapper.unread(userId); }
    public void read(Long userId, Long id) { mapper.read(userId,id,now()); }
    public void readAll(Long userId) { mapper.readAll(userId,now()); }
    // Order mutations and scheduled events hold the item lock, serializing event deduplication.
    public void send(Long userId, Long orderId, String event, String content) {
        mapper.insert(userId,orderId,orderId+":"+event,content,now());
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
}
