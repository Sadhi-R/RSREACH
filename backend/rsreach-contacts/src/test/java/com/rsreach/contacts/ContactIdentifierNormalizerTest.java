package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class ContactIdentifierNormalizerTest {

    @Test
    void normalizesEmailToLowercase() {
        assertThat(ContactIdentifierNormalizer.normalizeEmail("  Ada.Lovelace@Example.COM "))
                .isEqualTo("ada.lovelace@example.com");
    }

    @Test
    void rejectsInvalidEmail() {
        assertThatThrownBy(() -> ContactIdentifierNormalizer.normalizeEmail("not-an-email"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void normalizesPhoneToE164() {
        assertThat(ContactIdentifierNormalizer.normalizePhone("+91 98765-43210"))
                .isEqualTo("+919876543210");
        assertThat(ContactIdentifierNormalizer.normalizePhone("00919876543210"))
                .isEqualTo("+919876543210");
    }

    @Test
    void rejectsPhoneWithoutCountryCode() {
        assertThatThrownBy(() -> ContactIdentifierNormalizer.normalizePhone("9876543210"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
