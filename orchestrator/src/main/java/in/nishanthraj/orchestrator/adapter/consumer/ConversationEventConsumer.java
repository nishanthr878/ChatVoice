package in.nishanthraj.orchestrator.adapter.consumer;

import in.nishanthraj.orchestrator.TurnPayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;


@Component
public class ConversationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConversationEventConsumer.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ConversationEventConsumer (JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "conversation-events", groupId = "conversation-state-consumer")
    public void onMessage(ConsumerRecord<String, String> record) {
        String conversationId = record.key();
        log.info("Received turn for conversation {}: {}", conversationId, record.value());
        TurnPayload payload = parse(record.value());

        ensureConversationExists(conversationId);
        insertTurn(conversationId, payload);
        log.info("Persisted turn for conversation {}", conversationId);
    }

    private void ensureConversationExists(String conversationId) {
        jdbc.update("""
            INSERT INTO conversation (conversation_id, channel, flow_type, current_node, status)
            VALUES (?::uuid, 'chat', 'trivial_test', 'start', 'active')
            ON CONFLICT (conversation_id) DO NOTHING
            """, conversationId);

    }

    private void insertTurn(String conversationId, TurnPayload payload) {
        Integer nextSeq = jdbc.queryForObject("""
            SELECT COALESCE(MAX(sequence_number), 0) + 1
            FROM turn WHERE conversation_id = ?::uuid
            """, Integer.class, conversationId);

        jdbc.update("""
            INSERT INTO turn (turn_id, conversation_id, speaker, content, sequence_number)
            VALUES (gen_random_uuid(), ?::uuid, ?, ?, ?)
            """, conversationId, payload.speaker(), payload.content(), nextSeq);
    }

    private TurnPayload parse(String json) {
        return objectMapper.readValue(json, TurnPayload.class);
    }
}
