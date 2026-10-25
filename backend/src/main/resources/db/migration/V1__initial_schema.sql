-- MySQL 8.0+ / H2 MySQL mode. All DATETIME values represent UTC.
-- Flyway owns this migration. Create the database before first MySQL startup.
CREATE TABLE app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE auth_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_session_hash UNIQUE (token_hash),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX idx_session_user ON auth_session(user_id);
CREATE INDEX idx_session_expiry ON auth_session(expires_at);

CREATE TABLE category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_category_name UNIQUE (name)
);

CREATE TABLE item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT NOT NULL,
    campus VARCHAR(32) NOT NULL,
    pickup_location VARCHAR(150) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ON_SHELF',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_item_owner FOREIGN KEY (owner_id) REFERENCES app_user(id),
    CONSTRAINT fk_item_category FOREIGN KEY (category_id) REFERENCES category(id),
    CONSTRAINT ck_item_status CHECK (status IN ('ON_SHELF', 'OFF_SHELF'))
);
CREATE INDEX idx_item_owner_created ON item(owner_id, created_at);
CREATE INDEX idx_item_category_status ON item(category_id, status, created_at);

CREATE TABLE item_image (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    image_url VARCHAR(1024) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_image_item FOREIGN KEY (item_id) REFERENCES item(id)
);
CREATE INDEX idx_image_item ON item_image(item_id, sort_order);

CREATE TABLE borrow_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    borrower_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
    review_expires_at DATETIME(6) NOT NULL,
    pickup_expires_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_order_borrower FOREIGN KEY (borrower_id) REFERENCES app_user(id),
    CONSTRAINT fk_order_item FOREIGN KEY (item_id) REFERENCES item(id),
    CONSTRAINT ck_order_time CHECK (start_time < end_time),
    CONSTRAINT ck_order_status CHECK (status IN (
        'PENDING_REVIEW', 'AWAITING_PICKUP', 'BORROWING', 'RETURN_REQUESTED',
        'COMPLETED', 'REJECTED', 'CANCELLED', 'EXPIRED'
    ))
);
-- This index speeds up conflict queries; it DOES NOT prevent overlapping bookings.
-- Approval must lock the item row and check conflicts in the same transaction.
CREATE INDEX idx_order_conflict ON borrow_order(item_id, status, start_time, end_time);
CREATE INDEX idx_order_borrower_created ON borrow_order(borrower_id, created_at);
CREATE INDEX idx_order_review_expiry ON borrow_order(status, review_expires_at);
CREATE INDEX idx_order_pickup_expiry ON borrow_order(status, pickup_expires_at);

CREATE TABLE order_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    operator_id BIGINT,
    operator_type VARCHAR(16) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    remark VARCHAR(500),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_log_order FOREIGN KEY (order_id) REFERENCES borrow_order(id),
    CONSTRAINT fk_log_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT ck_log_operator CHECK (
        (operator_type = 'USER' AND operator_id IS NOT NULL) OR
        (operator_type = 'SYSTEM' AND operator_id IS NULL)
    )
);
CREATE INDEX idx_log_order ON order_operation_log(order_id, created_at);

INSERT INTO category (name, sort_order) VALUES
    ('数码摄影', 1), ('运动装备', 2), ('户外露营', 3), ('学习办公', 4), ('生活好物', 5);
