ALTER TABLE borrow_order ADD COLUMN picked_up_at DATETIME(6);
ALTER TABLE borrow_order ADD COLUMN renewal_end_time DATETIME(6);
ALTER TABLE borrow_order ADD COLUMN renewal_status VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE borrow_order ADD COLUMN renewal_sequence INT NOT NULL DEFAULT 0;
ALTER TABLE borrow_order ADD COLUMN cancel_reason VARCHAR(500);
ALTER TABLE borrow_order ADD CONSTRAINT ck_renewal_status CHECK (renewal_status IN ('NONE','PENDING','APPROVED','REJECTED','CANCELLED'));
CREATE INDEX idx_order_return_due ON borrow_order(status, end_time);

CREATE TABLE notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    content VARCHAR(700) NOT NULL,
    read_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_notification_order FOREIGN KEY (order_id) REFERENCES borrow_order(id),
    CONSTRAINT uk_notification_event UNIQUE (user_id, event_key)
);
CREATE INDEX idx_notification_user ON notification(user_id, created_at, id);
