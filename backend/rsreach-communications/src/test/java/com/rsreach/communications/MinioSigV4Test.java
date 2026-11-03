package com.rsreach.communications;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class MinioSigV4Test {

    private static final ZonedDateTime FIXED = ZonedDateTime.of(2026, 11, 3, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void authorizationHeaderUsesSigV4WhenKeysArePresent() throws Exception {
        String header = MinioObjectStorage.authorizationHeader(
                "fixture-access-key",
                "fixture-secret-key",
                "us-east-1",
                "PUT",
                URI.create("http://minio:9000/rsreach-media/tenant/file"),
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8),
                FIXED
        );

        assertThat(header).startsWith("AWS4-HMAC-SHA256 ");
        assertThat(header).contains("Credential=fixture-access-key/20261103/us-east-1/s3/aws4_request");
        assertThat(header).contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date");
        assertThat(header).containsPattern("Signature=[0-9a-f]{64}");
        assertThat(header).doesNotContain("fixture-secret-key");
    }

    @Test
    void signatureChangesWhenPayloadChanges() throws Exception {
        String first = sign("alpha".getBytes(StandardCharsets.UTF_8));
        String second = sign("beta".getBytes(StandardCharsets.UTF_8));
        assertThat(signatureOf(first)).isNotEqualTo(signatureOf(second));
    }

    private static String sign(byte[] payload) throws Exception {
        return MinioObjectStorage.authorizationHeader(
                "fixture-access-key",
                "fixture-secret-key",
                "us-east-1",
                "PUT",
                URI.create("http://minio:9000/rsreach-media/tenant/file"),
                "text/plain",
                payload,
                FIXED
        );
    }

    private static String signatureOf(String header) {
        int index = header.indexOf("Signature=");
        return header.substring(index + "Signature=".length());
    }
}
