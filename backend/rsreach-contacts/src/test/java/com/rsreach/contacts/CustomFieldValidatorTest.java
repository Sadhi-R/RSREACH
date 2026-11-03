package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomFieldValidatorTest {

    private final ContactModels.CustomFieldDef company = def("company", "STRING", false);
    private final ContactModels.CustomFieldDef score = def("score", "NUMBER", true);
    private final ContactModels.CustomFieldDef vip = def("vip", "BOOLEAN", false);
    private final ContactModels.CustomFieldDef joined = def("joined_on", "DATE", false);

    @Test
    void validatesAndCoercesSupportedTypes() {
        Map<String, Object> result = CustomFieldValidator.validateAndNormalize(
                Map.of(
                        "company", " Acme ",
                        "score", "12.5",
                        "vip", "true",
                        "joined_on", "2026-01-15"
                ),
                List.of(company, score, vip, joined)
        );
        assertThat(result.get("company")).isEqualTo("Acme");
        assertThat(result.get("score")).isEqualTo(new BigDecimal("12.5"));
        assertThat(result.get("vip")).isEqualTo(true);
        assertThat(result.get("joined_on")).isEqualTo("2026-01-15");
    }

    @Test
    void rejectsUnknownAndMissingRequiredFields() {
        assertThatThrownBy(() -> CustomFieldValidator.validateAndNormalize(
                Map.of("unknown", "x"), List.of(company)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> CustomFieldValidator.validateAndNormalize(Map.of(), List.of(score)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static ContactModels.CustomFieldDef def(String key, String type, boolean required) {
        return new ContactModels.CustomFieldDef(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                key, key, type, required, true
        );
    }
}
