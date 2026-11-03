package com.rsreach.communications;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * S3-compatible MinIO client using AWS Signature Version 4 (path-style).
 * Falls back to bearer auth only when access/secret keys are blank (local private networks).
 */
@Component
@ConditionalOnProperty(prefix = "rsreach.media", name = "object-store-enabled", havingValue = "true")
public class MinioObjectStorage implements ObjectStoragePort {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String endpoint;
    private final String bucket;
    private final String bearer;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public MinioObjectStorage(
            @Value("${rsreach.media.minio-endpoint:http://minio:9000}") String endpoint,
            @Value("${rsreach.media.minio-bucket:rsreach-media}") String bucket,
            @Value("${rsreach.media.minio-bearer:}") String bearer,
            @Value("${rsreach.media.minio-access-key:}") String accessKey,
            @Value("${rsreach.media.minio-secret-key:}") String secretKey,
            @Value("${rsreach.media.minio-region:us-east-1}") String region
    ) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.bucket = bucket;
        this.bearer = bearer;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region == null || region.isBlank() ? "us-east-1" : region;
    }

    @Override
    public String put(
            UUID tenantId,
            UUID workspaceId,
            UUID messageId,
            UUID attachmentId,
            String contentType,
            long sizeBytes,
            InputStream data
    ) {
        String key = tenantId + "/" + workspaceId + "/" + messageId + "/" + attachmentId;
        try {
            byte[] bytes = data.readAllBytes();
            String contentTypeHeader = contentType == null ? "application/octet-stream" : contentType;
            HttpRequest request = signedRequest("PUT", "/" + bucket + "/" + key, contentTypeHeader, bytes);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("minio_put_failed status=" + response.statusCode());
            }
            return "minio://" + bucket + "/" + key;
        } catch (Exception ex) {
            throw new IllegalStateException("minio_put_error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public InputStream get(String storageRef) {
        String path = canonicalPath(storageRef);
        try {
            HttpRequest request = signedRequest("GET", path, null, new byte[0]);
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("minio_get_failed status=" + response.statusCode());
            }
            return new ByteArrayInputStream(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("minio_get_error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String presignGet(String storageRef, int ttlSeconds) {
        return endpoint + canonicalPath(storageRef) + "?ttl=" + ttlSeconds;
    }

    private HttpRequest signedRequest(String method, String canonicalUri, String contentType, byte[] payload)
            throws Exception {
        URI uri = URI.create(endpoint + canonicalUri);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(60));
        if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
            builder.header("Content-Type", contentType);
        } else {
            builder.GET();
        }

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            applySigV4(builder, method, uri, contentType, payload);
        } else if (bearer != null && !bearer.isBlank()) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return builder.build();
    }

    private void applySigV4(
            HttpRequest.Builder builder,
            String method,
            URI uri,
            String contentType,
            byte[] payload
    ) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String authorization = authorizationHeader(
                accessKey, secretKey, region, method, uri, contentType, payload, now);
        String amzDate = now.format(AMZ_DATE);
        String payloadHash = sha256Hex(payload);
        builder.header("x-amz-date", amzDate);
        builder.header("x-amz-content-sha256", payloadHash);
        builder.header("Authorization", authorization);
    }

    /**
     * Builds an AWS SigV4 Authorization header for path-style S3/MinIO.
     * Package-visible for unit tests that must not call a live object store.
     */
    static String authorizationHeader(
            String accessKey,
            String secretKey,
            String region,
            String method,
            URI uri,
            String contentType,
            byte[] payload,
            ZonedDateTime now
    ) throws Exception {
        String amzDate = now.format(AMZ_DATE);
        String dateStamp = now.format(DATE_STAMP);
        String payloadHash = sha256Hex(payload);
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        if (contentType != null && !contentType.isBlank()) {
            canonicalHeaders = "content-type:" + contentType + "\n" + canonicalHeaders;
            signedHeaders = "content-type;" + signedHeaders;
        }
        String canonicalRequest = method + "\n"
                + uri.getRawPath() + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, "s3");
        String signature = HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private String canonicalPath(String storageRef) {
        if (storageRef == null || !storageRef.startsWith("minio://")) {
            throw new IllegalArgumentException("Invalid storage_ref");
        }
        return "/" + storageRef.substring("minio://".length());
    }

    private static byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName)
            throws Exception {
        byte[] kDate = hmacSha256(("AWS4" + key).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, regionName);
        byte[] kService = hmacSha256(kRegion, serviceName);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
