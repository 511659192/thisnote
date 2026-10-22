package com.thisnote.ext.attachmentimporter.controller;

import com.thisnote.ext.attachmentimporter.service.AttachmentImporterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/plugins/attachment-importer")
@RequiredArgsConstructor
@Slf4j
public class AttachmentImporterController {

    private final AttachmentImporterService importerService;

    @PostMapping("/import-folder")
    public AttachmentImporterService.FolderImportResult importFolder(@RequestBody ImportFolderRequest request) {
        log.info("收到文件夹图片导入请求：path={}", request.path());
        AttachmentImporterService.FolderImportResult result = importerService.importFolder(request.path());
        log.info("文件夹图片导入请求完成：path={}, copied={}, duplicate={}, skipped={}, failed={}",
                request.path(), result.imported(), result.duplicates(), result.skipped(), result.errors().size());
        return result;
    }

    @PostMapping(value = "/import-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AttachmentImporterService.FolderImportResult importUpload(@RequestParam("file") MultipartFile file) {
        String fileName = file == null ? "" : file.getOriginalFilename();
        log.info("收到图片 HTTP 上传请求：fileName={}, bytes={}", fileName, file == null ? 0 : file.getSize());
        AttachmentImporterService.FolderImportResult result = importerService.importUpload(file);
        log.info("图片 HTTP 上传请求完成：fileName={}, copied={}, duplicate={}, skipped={}, failed={}",
                fileName, result.imported(), result.duplicates(), result.skipped(), result.errors().size());
        return result;
    }

    public record ImportFolderRequest(String path) {
    }
}
