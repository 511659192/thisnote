package com.thisnote.plugin.api.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 附件图片引用的统一解析。
 */
public final class MarkdownImages {

    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\((<[^>]+>|(?:\\\\.|[^)])*)\\)");
    private static final Pattern ATTACHMENT = Pattern.compile(
            "(?i)(?:\\.\\./)?attachments/(.+?\\.(?:png|jpg|jpeg|webp))(?:[?#].*)?$");
    private static final Pattern SAFE_NAME = Pattern.compile("(?i)[^/\\\\]+\\.(?:png|jpg|jpeg|webp)");

    private MarkdownImages() {
    }

    public static List<Reference> attachmentReferences(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<Reference> references = new ArrayList<>();
        Matcher matcher = IMAGE.matcher(markdown);
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            while (start < end && Character.isWhitespace(markdown.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(markdown.charAt(end - 1))) {
                end--;
            }
            String originalTarget = markdown.substring(start, end);
            String target = originalTarget.startsWith("<") && originalTarget.endsWith(">")
                    ? originalTarget.substring(1, originalTarget.length() - 1)
                    : originalTarget;
            Matcher attachment = ATTACHMENT.matcher(target);
            if (!attachment.matches()) {
                continue;
            }
            String fileName = attachment.group(1).replace("%20", " ");
            if (SAFE_NAME.matcher(fileName).matches()) {
                references.add(new Reference(originalTarget, fileName, start, end));
            }
        }
        return references;
    }

    public record Reference(String originalTarget, String fileName, int start, int end) {
    }
}
