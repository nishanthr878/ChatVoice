    package in.nishanthraj.orchestrator.adapter.consumer;


    import in.nishanthraj.orchestrator.domain.orchestration.GraphExecutor;
    import org.apache.kafka.clients.consumer.ConsumerRecord;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    import org.springframework.kafka.annotation.KafkaListener;
    import org.springframework.stereotype.Component;
    import tools.jackson.databind.ObjectMapper;

    @Component
    public class ConversationEventConsumer {

        private static final Logger log = LoggerFactory.getLogger(ConversationEventConsumer.class);

        private final GraphExecutor graphExecutor;
        private final ObjectMapper objectMapper;

        public ConversationEventConsumer(GraphExecutor graphExecutor, ObjectMapper objectMapper) {
            this.graphExecutor = graphExecutor;
            this.objectMapper = objectMapper;
        }

        @KafkaListener(topics = "conversation-events", groupId = "conversation-state-consumer")
        public void onMessage(ConsumerRecord<String, String> record) {
            String conversationId = record.key();
            log.info("Received turn for conversation {}: {}", conversationId, record.value());

            TurnPayload payload = parse(record.value());
            String response = graphExecutor.step(conversationId, payload.content());

            log.info("Persisted turn for conversation {}, response: {}", conversationId, response);
        }

        private TurnPayload parse(String json) {
            return objectMapper.readValue(json, TurnPayload.class);
        }

    }
