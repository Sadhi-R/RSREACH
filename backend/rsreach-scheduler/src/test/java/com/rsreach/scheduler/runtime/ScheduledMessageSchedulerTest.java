package com.rsreach.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ScheduledMessageSchedulerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private OutboxRepository outboxRepository;

    @Test
    void fireDueMarksFiredAndAppendsOutbox() throws Exception {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Object dueRow = newDueRow(id, tenantId, workspaceId);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class))).thenReturn(List.of(dueRow));
        when(jdbcTemplate.update(any(String.class), eq(id))).thenReturn(1);

        ScheduledMessageScheduler scheduler = new ScheduledMessageScheduler(jdbcTemplate, outboxRepository);
        scheduler.fireDue();

        verify(jdbcTemplate).update(any(String.class), eq(id));
        ArgumentCaptor<OutboxAppendCommand> captor = ArgumentCaptor.forClass(OutboxAppendCommand.class);
        verify(outboxRepository).append(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("SCHEDULED_MESSAGE_FIRE");
        assertThat(captor.getValue().aggregateId()).isEqualTo(id);
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(captor.getValue().workspaceId()).isEqualTo(workspaceId);
    }

    private static Object newDueRow(UUID id, UUID tenantId, UUID workspaceId) throws Exception {
        Class<?> dueRow = Class.forName("com.rsreach.scheduler.runtime.ScheduledMessageScheduler$DueRow");
        Constructor<?> ctor = dueRow.getDeclaredConstructor(UUID.class, UUID.class, UUID.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id, tenantId, workspaceId);
    }
}
