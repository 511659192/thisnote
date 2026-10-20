package com.thisnote.ext.attachmentimporter.controller;

import com.thisnote.ext.attachmentimporter.service.AttachmentImporterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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

    public record ImportFolderRequest(String path) {
    }
}
