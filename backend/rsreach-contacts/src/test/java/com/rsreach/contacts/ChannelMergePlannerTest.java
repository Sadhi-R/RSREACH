package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsreach.common.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelMergePlannerTest {

    private final UUID survivorId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID loserId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void movesActiveLoserChannelsAndLeavesArchivedInvalid() {
        UUID survEmail = UUID.randomUUID();
        UUID loserEmail = UUID.randomUUID();
        UUID loserArchived = UUID.randomUUID();
        UUID loserInvalid = UUID.randomUUID();
        ChannelMergePlanner.Plan plan = ChannelMergePlanner.plan(
                survivorId,
                loserId,
                List.of(channel(survEmail, "EMAIL", "a@example.com", true, "ACTIVE")),
                List.of(
                        channel(loserEmail, "EMAIL", "b@example.com", true, "ACTIVE"),
                        channel(loserArchived, "EMAIL", "old@example.com", false, "ARCHIVED"),
                        channel(loserInvalid, "PHONE", "+919800000001", true, "INVALID")
                ),
                List.of()
        );
        assertThat(plan.moveChannelIds()).containsExactly(loserEmail);
        assertThat(plan.dedupeLoserChannelIds()).isEmpty();
        assertThat(plan.introducedByType().get("EMAIL")).containsExactly("b@example.com");
        assertThat(plan.dispositions()).anyMatch(d -> d.channelId().equals(loserArchived) && "LEFT_ARCHIVED".equals(d.action()));
        assertThat(plan.dispositions()).anyMatch(d -> d.channelId().equals(loserInvalid) && "LEFT_INVALID".equals(d.action()));
    }

    @Test
    void retainsSurvivorArchivedWithoutReactivation() {
        UUID survArchived = UUID.randomUUID();
        UUID survActive = UUID.randomUUID();
        UUID loserEmail = UUID.randomUUID();
        ChannelMergePlanner.Plan plan = ChannelMergePlanner.plan(
                survivorId,
                loserId,
                List.of(
                        channel(survActive, "EMAIL", "live@example.com", true, "ACTIVE"),
                        channel(survArchived, "EMAIL", "old-survivor@example.com", false, "ARCHIVED")
                ),
                List.of(channel(loserEmail, "EMAIL", "b@example.com", true, "ACTIVE")),
                List.of()
        );
        assertThat(plan.dispositions()).anyMatch(d ->
                d.channelId().equals(survArchived)
                        && "RETAINED".equals(d.action())
                        && "ARCHIVED".equals(d.status()));
        assertThat(plan.postMergeActiveByType().get("EMAIL"))
                .contains("live@example.com", "b@example.com")
                .doesNotContain("old-survivor@example.com");
        assertThat(plan.introducedByType().get("EMAIL")).containsExactly("b@example.com");
    }

    @Test
    void dedupesIdenticalActiveValuesWithoutIntroducing() {
        UUID survEmail = UUID.randomUUID();
        UUID loserEmail = UUID.randomUUID();
        ChannelMergePlanner.Plan plan = ChannelMergePlanner.plan(
                survivorId,
                loserId,
                List.of(channel(survEmail, "EMAIL", "same@example.com", true, "ACTIVE")),
                List.of(channel(loserEmail, "EMAIL", "same@example.com", true, "ACTIVE")),
                List.of()
        );
        assertThat(plan.moveChannelIds()).isEmpty();
        assertThat(plan.dedupeLoserChannelIds()).containsExactly(loserEmail);
        assertThat(plan.introducedByType()).doesNotContainKey("EMAIL");
        assertThat(plan.postMergeActiveByType().get("EMAIL")).containsExactly("same@example.com");
    }

    @Test
    void keepsSurvivorPrimaryByDefaultAndAllowsExplicitChoice() {
        UUID survEmail = UUID.randomUUID();
        UUID loserEmail = UUID.randomUUID();
        ChannelMergePlanner.Plan defaults = ChannelMergePlanner.plan(
                survivorId, loserId,
                List.of(channel(survEmail, "EMAIL", "a@example.com", true, "ACTIVE")),
                List.of(channel(loserEmail, "EMAIL", "b@example.com", true, "ACTIVE")),
                List.of()
        );
        assertThat(defaults.primaryByType().get("EMAIL")).isEqualTo(survEmail);

        ChannelMergePlanner.Plan chosen = ChannelMergePlanner.plan(
                survivorId, loserId,
                List.of(channel(survEmail, "EMAIL", "a@example.com", true, "ACTIVE")),
                List.of(channel(loserEmail, "EMAIL", "b@example.com", true, "ACTIVE")),
                List.of(new MergeModels.PrimaryChoice("EMAIL", loserEmail))
        );
        assertThat(chosen.primaryByType().get("EMAIL")).isEqualTo(loserEmail);
    }

    @Test
    void rejectsPrimaryChoiceNotInPostMergeActiveSet() {
        UUID survEmail = UUID.randomUUID();
        UUID loserArchived = UUID.randomUUID();
        assertThatThrownBy(() -> ChannelMergePlanner.plan(
                survivorId, loserId,
                List.of(channel(survEmail, "EMAIL", "a@example.com", true, "ACTIVE")),
                List.of(channel(loserArchived, "EMAIL", "b@example.com", false, "ARCHIVED")),
                List.of(new MergeModels.PrimaryChoice("EMAIL", loserArchived))
        )).isInstanceOf(ApiException.class);
    }

    @Test
    void fingerprintIsDeterministic() {
        UUID survEmail = UUID.randomUUID();
        UUID loserEmail = UUID.randomUUID();
        List<ContactModels.ChannelView> survivor = List.of(channel(survEmail, "EMAIL", "a@example.com", true, "ACTIVE"));
        List<ContactModels.ChannelView> loser = List.of(channel(loserEmail, "EMAIL", "b@example.com", true, "ACTIVE"));
        String a = ChannelMergePlanner.plan(survivorId, loserId, survivor, loser, List.of()).channelFingerprint();
        String b = ChannelMergePlanner.plan(survivorId, loserId, survivor, loser, List.of()).channelFingerprint();
        assertThat(a).isEqualTo(b);
    }

    private ContactModels.ChannelView channel(
            UUID id, String type, String value, boolean primary, String status
    ) {
        return new ContactModels.ChannelView(id, type, value, value, primary, null, status);
    }
}
