package com.campus.share.controller;

import com.campus.share.common.ApiResponse;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.service.MediaService;
import com.campus.share.vo.MediaFile;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MediaController {
    private final MediaService service;
    public MediaController(MediaService service) { this.service = service; }
    @PostMapping(value="/api/media", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, String>> upload(@RequestPart("file") MultipartFile file, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(Map.of("url", service.upload(file, user.id())));
    }
    @GetMapping("/api/media/{name}")
    public ResponseEntity<Resource> read(@PathVariable String name) {
        MediaFile file = service.read(name);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header("X-Content-Type-Options", "nosniff").body(file.resource());
    }
}
