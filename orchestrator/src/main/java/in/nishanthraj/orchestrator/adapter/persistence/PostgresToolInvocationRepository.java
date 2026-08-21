package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.port.ToolInvocationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PostgresToolInvocationRepository implements ToolInvocationRepository {

    private final JdbcTemplate jdbc;

    public PostgresToolInvocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String idempotencyKey(String conversationId, String turnId, String toolName) {
        return conversationId + ":" + turnId + ":" + toolName;
    }

    @Override
    public Optional<String> getResultIfCompleted(String conversationId, String turnId, String toolName) {
        return jdbc.query("""
                SELECT result #>> '{}' FROM tool_invocation
                WHERE idempotency_key = ? AND status = 'executed'
                """, (rs, rowNum) -> rs.getString(1), idempotencyKey(conversationId, turnId, toolName))
                .stream().findFirst();
    }

    @Override
    public void recordCallStarting(String conversationId, String turnId, String toolName, String arguments) {
        jdbc.update("""
                INSERT INTO tool_invocation (invocation_id, conversation_id, idempotency_key, tool_name, arguments, status)
                VALUES (gen_random_uuid(), ?::uuid, ?, ?, to_jsonb(?::text), 'pending')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, conversationId, idempotencyKey(conversationId, turnId, toolName), toolName, arguments);
    }

    @Override
    public void recordCallFinished(String conversationId, String turnId, String toolName, String result, String status) {
        jdbc.update("""
                UPDATE tool_invocation
                SET result = to_jsonb(?::text), status = ?, updated_at = now()
                WHERE idempotency_key = ?
                """, result, status, idempotencyKey(conversationId, turnId, toolName));
    }
}
