package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.ConversationRepository;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;


@Component
public class PostgresConversationRepository implements ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresConversationRepository.class);

    private final JdbcTemplate jdbc;

    public PostgresConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(String conversationId) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 from conversation WHERE conversation_id = ?::uuid)",
                Boolean.class, conversationId
        );
    }

    @Override
    public String getCurrentNode(String conversationId) {
        return jdbc.queryForObject(
                "SELECT current_node from conversation WHERE conversation_id = ?::uuid",
                String.class, conversationId
        );
    }

    @Override
    public void updateCurrentNode(String conversationId, String newNode) {
        jdbc.update(
                "UPDATE conversation SET current_node = ?, updated_at = NOW() WHERE conversation_id = ?::uuid",
                newNode, conversationId
        );
    }

    @Override
    public void create(String conversationId, String channel, String flowType, String initialNode) {
        jdbc.update(
                "INSERT INTO conversation (conversation_id, channel, flow_type, current_node, status) \n" +
                        "VALUES (?::uuid, ?, ?, ?, 'active')",
                conversationId, channel, flowType, initialNode
        );
    }

}

