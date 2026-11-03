package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsreach.common.error.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContactCsvSupportTest {

    @Test
    void parsesQuotedCommasAndNormalizesHeaders() {
        String csv = "display_name,email,phone\n\"Doe, Jane\",jane@example.com,+15551234567\n";
        ContactCsvSupport.ParsedCsv parsed = ContactCsvSupport.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                ContactImportModels.MAX_UPLOAD_BYTES,
                ContactImportModels.MAX_ROWS
        );
        assertThat(parsed.headers()).containsExactly("display_name", "email", "phone");
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).get("display_name")).isEqualTo("Doe, Jane");
    }

    @Test
    void rejectsOverMaxRows() {
        StringBuilder sb = new StringBuilder("display_name,email\n");
        for (int i = 0; i < 3; i++) {
            sb.append("Name ").append(i).append(",user").append(i).append("@example.com\n");
        }
        assertThatThrownBy(() -> ContactCsvSupport.parse(
                new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                ContactImportModels.MAX_UPLOAD_BYTES,
                2
        )).isInstanceOf(ApiException.class);
    }

    @Test
    void escapesFormulaInjectionOnExport() {
        assertThat(ContactCsvSupport.escapeFormula("=CMD()")).isEqualTo("'=CMD()");
        assertThat(ContactCsvSupport.escapeFormula("+1234")).isEqualTo("'+1234");
        assertThat(ContactCsvSupport.escapeFormula("safe")).isEqualTo("safe");
        assertThat(ContactCsvSupport.toCsvLine(List.of("=1+1", "ok"))).isEqualTo("'=1+1,ok");
    }
}
