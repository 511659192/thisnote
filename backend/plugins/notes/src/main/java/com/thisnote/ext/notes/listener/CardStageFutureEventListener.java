package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.plugin.api.event.CardStageFutureEvent;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

@RequiredArgsConstructor
@Slf4j
public class CardStageFutureEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardStageFutureEvent>> {

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardStageFutureEvent> event) {
        CardStageFutureEvent request = event.getPayload();
        Path source = null;
        String fileName = "";
        try {
            source = Path.of(request.sourcePath()).toAbsolutePath().normalize();
            fileName = source.getFileName() == null ? "" : source.getFileName().toString();
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("源卡片不存在或不是普通文件：" + source);
            }
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
                throw new IllegalArgumentException("只支持 Markdown 卡片文件：" + fileName);
            }
            Path destination = notesConfig.getRootDir().resolve("cards").resolve(fileName);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                log.debug("跳过同名待导入卡片：fileName={}", fileName);
                request.result().complete(new CardStageFutureEvent.CardStageResult(fileName, true));
                return;
            }
            long modifiedAt = Files.getLastModifiedTime(source).toMillis();
            fileHelper.fileWritten(destination, Files.newInputStream(source), modifiedAt);
            log.info("卡片已复制到待导入目录：source={}, destination={}", source, destination);
            request.result().complete(new CardStageFutureEvent.CardStageResult(fileName, false));
        } catch (Exception e) {
            log.error("卡片暂存失败：sourcePath={}, fileName={}", request.sourcePath(), fileName, e);
            request.result().completeExceptionally(e);
        }
    }
}
