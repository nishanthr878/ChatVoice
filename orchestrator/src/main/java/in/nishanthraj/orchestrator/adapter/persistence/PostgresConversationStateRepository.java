package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.port.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PostgresConversationStateRepository implements ConversationStateRepository {

    private final JdbcTemplate jdbc;

    public PostgresConversationStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConversationState getOrCreate(String conversationId) {
        jdbc.update("""
                INSERT INTO conversation_state (conversation_id, version)
                VALUES (?::uuid, 1)
                ON CONFLICT (conversation_id) DO NOTHING
                """, conversationId);

        return jdbc.queryForObject("""
                SELECT conversation_id, active_intent, active_focus_entity_type, active_focus_entity_id, version
                FROM conversation_state WHERE conversation_id = ?::uuid
                """, this::mapRow, conversationId);
    }

    @Override
    public ConversationState applyUpdate(String conversationId, ConversationStateUpdate update, int expectedVersion) {
        ConversationState current = jdbc.queryForObject("""
                SELECT conversation_id, active_intent, active_focus_entity_type, active_focus_entity_id, version
                FROM conversation_state WHERE conversation_id = ?::uuid
                """, this::mapRow, conversationId);

        String newIntent = update.activeIntent().orElse(current.activeIntent());
        Optional<EntityReference> newFocus = update.activeFocus().isPresent()
                ? update.activeFocus()
                : current.activeFocus();

        int rowsUpdated = jdbc.update("""
                        UPDATE conversation_state
                        SET active_intent = ?, active_focus_entity_type = ?, active_focus_entity_id = ?,
                            version = version + 1, updated_at = NOW()
                        WHERE conversation_id = ?::uuid AND version = ?
                        """,
                newIntent,
                newFocus.map(EntityReference::entityType).orElse(null),
                newFocus.map(EntityReference::entityId).orElse(null),
                conversationId, expectedVersion);

        if (rowsUpdated == 0) {
            throw new OptimisticLockException(
                    "Expected version " + expectedVersion + " but update affected 0 rows for conversation " + conversationId);
        }

        for (EntityReference entity : update.newEntities()) {
            jdbc.update("""
                    INSERT INTO conversation_entity (conversation_id, entity_type, entity_id)
                    VALUES (?::uuid, ?, ?)
                    ON CONFLICT (conversation_id, entity_type, entity_id) DO NOTHING
                    """, conversationId, entity.entityType(), entity.entityId());
        }

        return new ConversationState(conversationId, newIntent, newFocus, expectedVersion + 1);
    }

    @Override
    public List<EntityReference> getEntities(String conversationId, String entityType) {
        return jdbc.query("""
                        SELECT entity_type, entity_id FROM conversation_entity
                        WHERE conversation_id = ?::uuid AND entity_type = ?
                        ORDER BY created_at
                        """,
                (rs, rowNum) -> new EntityReference(rs.getString("entity_type"), rs.getString("entity_id")),
                conversationId, entityType);
    }

    private ConversationState mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String focusType = rs.getString("active_focus_entity_type");
        String focusId = rs.getString("active_focus_entity_id");
        Optional<EntityReference> focus = (focusType != null && focusId != null)
                ? Optional.of(new EntityReference(focusType, focusId))
                : Optional.empty();

        return new ConversationState(
                rs.getString("conversation_id"),
                rs.getString("active_intent"),
                focus,
                rs.getInt("version")
        );
    }
}