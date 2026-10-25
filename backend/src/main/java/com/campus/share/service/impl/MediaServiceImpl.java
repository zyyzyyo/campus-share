package com.campus.share.service.impl;

import com.campus.share.common.BusinessException;
import com.campus.share.entity.UploadedImage;
import com.campus.share.mapper.MediaMapper;
import com.campus.share.service.MediaService;
import com.campus.share.vo.MediaFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaServiceImpl implements MediaService {
    private final Path directory;
    private final MediaMapper mapper;
    public MediaServiceImpl(@Value("${app.uploads.directory}") String directory, MediaMapper mapper) {
        this.directory = Path.of(directory).toAbsolutePath().normalize(); this.mapper = mapper;
    }
    public String upload(MultipartFile file, Long userId) {
        if (file.isEmpty() || file.getSize() > 1024 * 1024) throw invalid("请选择 1MB 以内的 JPG、PNG 或 WebP 图片");
        Path target = null;
        try {
            byte[] data = file.getBytes();
            String extension = detect(data);
            String type = switch (extension) { case "jpg" -> "image/jpeg"; case "png" -> "image/png"; default -> "image/webp"; };
            String name = UUID.randomUUID() + "." + extension;
            Files.createDirectories(directory);
            target = directory.resolve(name);
            Files.write(target, data, StandardOpenOption.CREATE_NEW);
            mapper.insert(new UploadedImage(name, userId, type));
            return "/api/media/" + name;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_FAILED", "图片保存失败，请稍后重试");
        } catch (RuntimeException exception) {
            if (target != null) try { Files.deleteIfExists(target); } catch (IOException ignored) { /* Keep original failure. */ }
            throw exception;
        }
    }
    public MediaFile read(String name) {
        if (!name.matches("[a-f0-9-]{36}\\.(jpg|png|webp)")) throw missing();
        UploadedImage image = mapper.find(name);
        Path path = directory.resolve(name).normalize();
        if (image == null || !path.startsWith(directory) || !Files.isRegularFile(path)) throw missing();
        return new MediaFile(new FileSystemResource(path), image.contentType());
    }
    private String detect(byte[] bytes) {
        if (bytes.length >= 12) {
            if ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "jpg";
            byte[] png = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
            if (java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, 8), png)) return "png";
            if (bytes[0]=='R' && bytes[1]=='I' && bytes[2]=='F' && bytes[3]=='F'
                    && bytes[8]=='W' && bytes[9]=='E' && bytes[10]=='B' && bytes[11]=='P') return "webp";
        }
        throw invalid("文件内容不是支持的 JPG、PNG 或 WebP 图片");
    }
    private BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", message); }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "图片不存在"); }
}
