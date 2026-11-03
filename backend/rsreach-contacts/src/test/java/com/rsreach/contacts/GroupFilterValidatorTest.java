package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GroupFilterValidatorTest {

    private final ContactModels.CustomFieldDef city = new ContactModels.CustomFieldDef(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "city", "City", "STRING", false, true
    );

    @Test
    void acceptsAllowListedAllMatchFilter() {
        GroupFilterModels.FilterDefinition def = GroupFilterValidator.parseAndValidate(
                Map.of(
                        "version", 1,
                        "match", "ALL",
                        "conditions", List.of(
                                Map.of("field", "display_name", "op", "contains", "value", "Ada"),
                                Map.of("field", "custom.city", "op", "eq", "value", "Pune")
                        )
                ),
                List.of(city)
        );
        assertThat(def.match()).isEqualTo("ALL");
        assertThat(def.conditions()).hasSize(2);
        GroupFilterQueryBuilder.BuiltFilter built = GroupFilterQueryBuilder.build(def);
        assertThat(built.whereSql()).contains("display_name");
        assertThat(built.whereSql()).contains("custom_fields");
        assertThat(built.whereSql()).doesNotContain("DROP");
    }

    @Test
    void rejectsSqlInjectionAndConsentAndUnknownFields() {
        assertThatThrownBy(() -> GroupFilterValidator.parseAndValidate(
                Map.of(
                        "version", 1,
                        "match", "ALL",
                        "conditions", List.of(Map.of("field", "display_name; drop table", "op", "eq", "value", "x"))
                ),
                List.of()
        )).extracting(ex -> ((ApiException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> GroupFilterValidator.parseAndValidate(
                Map.of(
                        "version", 1,
                        "match", "ALL",
                        "conditions", List.of(Map.of("field", "custom.missing", "op", "eq", "value", "x"))
                ),
                List.of(city)
        )).extracting(ex -> ((ApiException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void acceptsAllowListedConsentField() {
        GroupFilterModels.FilterDefinition def = GroupFilterValidator.parseAndValidate(
                Map.of(
                        "version", 1,
                        "match", "ALL",
                        "conditions", List.of(
                                Map.of("field", "consent.EMAIL.MARKETING", "op", "eq", "value", "OPTED_IN")
                        )
                ),
                List.of()
        );
        assertThat(def.conditions()).hasSize(1);
        assertThat(GroupFilterQueryBuilder.build(def).whereSql()).contains("contact_consents");
    }
}
