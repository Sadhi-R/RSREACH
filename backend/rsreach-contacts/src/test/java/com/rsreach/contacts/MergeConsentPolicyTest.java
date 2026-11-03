package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MergeConsentPolicyTest {

    @Test
    void optedInPlusUnknownBecomesUnknown() {
        MergeConsentPolicy.Decision d = MergeConsentPolicy.reconcile(
                Map.of("EMAIL:MARKETING", "OPTED_IN"),
                Map.of("EMAIL:MARKETING", "UNKNOWN"),
                Map.of()
        );
        assertThat(cell(d, "EMAIL", "MARKETING").after()).isEqualTo("UNKNOWN");
    }

    @Test
    void anyOptedOutWins() {
        MergeConsentPolicy.Decision d = MergeConsentPolicy.reconcile(
                Map.of("EMAIL:MARKETING", "OPTED_IN"),
                Map.of("EMAIL:MARKETING", "OPTED_OUT"),
                Map.of()
        );
        assertThat(cell(d, "EMAIL", "MARKETING").after()).isEqualTo("OPTED_OUT");
    }

    @Test
    void introducedActiveEmailForcesUnknownForAllEmailPurposes() {
        MergeConsentPolicy.Decision d = MergeConsentPolicy.reconcile(
                Map.of(
                        "EMAIL:MARKETING", "OPTED_IN",
                        "EMAIL:TRANSACTIONAL", "OPTED_IN",
                        "EMAIL:SERVICE", "OPTED_IN"
                ),
                Map.of(
                        "EMAIL:MARKETING", "OPTED_IN",
                        "EMAIL:TRANSACTIONAL", "OPTED_IN",
                        "EMAIL:SERVICE", "OPTED_IN"
                ),
                Map.of("EMAIL", Set.of("new@example.com"))
        );
        assertThat(cell(d, "EMAIL", "MARKETING").after()).isEqualTo("UNKNOWN");
        assertThat(cell(d, "EMAIL", "TRANSACTIONAL").after()).isEqualTo("UNKNOWN");
        assertThat(cell(d, "EMAIL", "SERVICE").after()).isEqualTo("UNKNOWN");
        assertThat(d.requiresFreshConsent()).isTrue();
    }

    @Test
    void overlappingOnlyMayRemainOptedIn() {
        MergeConsentPolicy.Decision d = MergeConsentPolicy.reconcile(
                Map.of("EMAIL:MARKETING", "OPTED_IN"),
                Map.of("EMAIL:MARKETING", "OPTED_IN"),
                Map.of()
        );
        assertThat(cell(d, "EMAIL", "MARKETING").after()).isEqualTo("OPTED_IN");
        assertThat(cell(d, "EMAIL", "MARKETING").changed()).isFalse();
    }

    @Test
    void introducedPhoneForcesWhatsappUnknown() {
        MergeConsentPolicy.Decision d = MergeConsentPolicy.reconcile(
                Map.of("WHATSAPP:MARKETING", "OPTED_IN"),
                Map.of("WHATSAPP:MARKETING", "OPTED_IN"),
                Map.of("PHONE", Set.of("+919811100001"))
        );
        assertThat(cell(d, "WHATSAPP", "MARKETING").after()).isEqualTo("UNKNOWN");
    }

    @Test
    void introducedDoesNotOverrideOptedOut() {
        MergeConsentPolicy.Decision d = MergeConsentPolicy.reconcile(
                Map.of("EMAIL:MARKETING", "OPTED_OUT"),
                Map.of("EMAIL:MARKETING", "OPTED_IN"),
                Map.of("EMAIL", Set.of("new@example.com"))
        );
        assertThat(cell(d, "EMAIL", "MARKETING").after()).isEqualTo("OPTED_OUT");
    }

    @Test
    void missingCellsTreatedAsUnknown() {
        MergeConsentPolicy.Decision d = MergeConsentPolicy.reconcile(Map.of(), Map.of(), Map.of());
        assertThat(cell(d, "EMAIL", "MARKETING").after()).isEqualTo("UNKNOWN");
        assertThat(cell(d, "WHATSAPP", "SERVICE").survivorBefore()).isEqualTo("UNKNOWN");
    }

    private MergeModels.ConsentCellDiff cell(MergeConsentPolicy.Decision d, String channel, String purpose) {
        return d.cells().stream()
                .filter(c -> channel.equals(c.channelType()) && purpose.equals(c.purpose()))
                .findFirst()
                .orElseThrow();
    }
}
