package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.port.SlotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PostgresSlotRepository implements SlotRepository {

    private final JdbcTemplate jdbc;

    public PostgresSlotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveSlot(String conversationId, String slotName, String slotValue) {
        jdbc.update("""
            INSERT INTO slot (conversation_id, slot_name, slot_value)
            VALUES (?::uuid, ?, to_jsonb(?::text))
            ON CONFLICT (conversation_id, slot_name)
            DO UPDATE SET slot_value = EXCLUDED.slot_value, filled_at = now()
            """, conversationId, slotName, slotValue);
    }

    @Override
    public Optional<String> getSlot(String conversationId, String slotName) {
        List<String> results = jdbc.query("""
            SELECT slot_value #>> '{}' FROM slot
            WHERE conversation_id = ?::uuid AND slot_name = ?
            """, (rs, rowNum) -> rs.getString(1), conversationId, slotName);

        return results.stream().findFirst();
    }
}
