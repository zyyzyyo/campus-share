CREATE TABLE favorite (
    user_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, item_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_favorite_item FOREIGN KEY (item_id) REFERENCES item(id)
);

CREATE TABLE uploaded_image (
    storage_name VARCHAR(80) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_upload_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
