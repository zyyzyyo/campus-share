package com.campus.share.vo;

import org.springframework.core.io.Resource;

public record MediaFile(Resource resource, String contentType) { }
