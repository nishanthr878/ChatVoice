package in.nishanthraj.orchestrator.adapter.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers
class ConversationEventConsumerTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine").withInitScript("init.sql");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private String conversationId;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID().toString();
    }

    @Test
    void publishedMessageResultsInPersistedConversationAndTurn() {
        String payload = "{\"speaker\":\"user\",\"content\":\"integration test message\"}";
        kafkaTemplate.send("conversation-events", conversationId, payload);

        await().atMost(30, TimeUnit.SECONDS)
                .ignoreExceptions()
                .untilAsserted(() -> {
                    String currentNode = jdbc.queryForObject(
                            "SELECT current_node FROM conversation WHERE conversation_id = ?::uuid",
                            String.class, conversationId
                    );
                    // "integration test message" contains no order number, so collect_order_id's
                    // LLM extraction should return NONE and the node stays at collect_order_id
                    assertEquals("collect_order_id", currentNode);
                });
    }
}