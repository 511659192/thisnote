package com.thisnode.ext.notes.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String shortId() {
        return newId().substring(0, 8);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Encode one path segment for attachment URLs. */
    public static String urlEncode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String urlDecode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }
}
