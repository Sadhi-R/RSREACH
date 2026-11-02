package com.rsreach.identity.staff;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Minimal TOTP (RFC 6238) foundation using HmacSHA1, 30s step, 6 digits.
 */
@Service
public class TotpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public String generateSecret() {
        byte[] buf = new byte[20];
        RANDOM.nextBytes(buf);
        return base32(buf);
    }

    public boolean verify(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long timestep = Instant.now().getEpochSecond() / 30;
        for (long skew = -1; skew <= 1; skew++) {
            if (generateCode(base32Secret, timestep + skew).equals(code)) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(String base32Secret, long timestep) {
        try {
            byte[] key = decodeBase32(base32Secret);
            byte[] data = ByteBuffer.allocate(8).putLong(timestep).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception ex) {
            throw new IllegalStateException("TOTP generation failed", ex);
        }
    }

    private static String base32(byte[] data) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(alphabet.charAt((buffer >> (bitsLeft - 5)) & 31));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(alphabet.charAt((buffer << (5 - bitsLeft)) & 31));
        }
        return out.toString();
    }

    private static byte[] decodeBase32(String encoded) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String s = encoded.trim().toUpperCase().replace("=", "");
        ByteBuffer buffer = ByteBuffer.allocate((s.length() * 5) / 8);
        int bitBuffer = 0;
        int bitsLeft = 0;
        for (char c : s.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) {
                continue;
            }
            bitBuffer = (bitBuffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                buffer.put((byte) ((bitBuffer >> (bitsLeft - 8)) & 0xFF));
                bitsLeft -= 8;
            }
        }
        byte[] out = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(out);
        return out;
    }
}
