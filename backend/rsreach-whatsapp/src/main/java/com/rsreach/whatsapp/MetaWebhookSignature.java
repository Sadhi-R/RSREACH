package com.rsreach.whatsapp;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class MetaWebhookSignature {

    private MetaWebhookSignature() {
    }

    public static boolean verifySha256(String appSecret, String header, String rawBody) {
        if (appSecret == null || appSecret.isBlank() || header == null || rawBody == null) {
            return false;
        }
        String expected = "sha256=" + hmacHex(appSecret, rawBody);
        return constantTimeEquals(expected, header.trim());
    }

    public static String hmacHex(String appSecret, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC failed", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
