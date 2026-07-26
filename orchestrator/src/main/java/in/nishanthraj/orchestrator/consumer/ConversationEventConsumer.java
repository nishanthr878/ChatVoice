package in.nishanthraj.orchestrator.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConversationEventConsumer {

    private final JdbcTemplate jbbc;

    public ConversationEventConsumer (JdbcTemplate jdbc) {
        this.jbbc = jdbc;
    }

    @KafkaListener(topics = "conversation-events", groupId = "conversation-state-consumer")
    public void onMessage(ConsumerRecord<String, String> record) {
        String conversationId = record.key();
        TurnPayload payload = parse(record.value());
    }
}
