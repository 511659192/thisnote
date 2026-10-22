package com.thisnote.ext.cardimporter.controller;

import com.thisnote.ext.cardimporter.service.CardImporterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/plugins/card-importer")
@RequiredArgsConstructor
@Slf4j
public class CardImporterController {

    private final CardImporterService importerService;

    @PostMapping("/import-folder")
    public CardImporterService.FolderImportResult importFolder(@RequestBody ImportFolderRequest request) {
        log.info("收到卡片文件夹导入请求：path={}", request.path());
        CardImporterService.FolderImportResult result = importerService.importFolder(request.path());
        log.info("卡片文件夹导入请求完成：path={}, copied={}, duplicate={}, skipped={}, failed={}",
                request.path(), result.imported(), result.duplicates(), result.skipped(), result.errors().size());
        return result;
    }

    @PostMapping(value = "/import-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CardImporterService.FolderImportResult importUpload(@RequestParam("file") MultipartFile file) {
        String fileName = file == null ? "" : file.getOriginalFilename();
        log.info("收到卡片 HTTP 上传请求：fileName={}, bytes={}", fileName, file == null ? 0 : file.getSize());
        CardImporterService.FolderImportResult result = importerService.importUpload(file);
        log.info("卡片 HTTP 上传请求完成：fileName={}, copied={}, duplicate={}, skipped={}, failed={}",
                fileName, result.imported(), result.duplicates(), result.skipped(), result.errors().size());
        return result;
    }

    public record ImportFolderRequest(String path) {
    }
}
