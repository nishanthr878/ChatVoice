package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.port.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class PostgresToolInvocationRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine").withInitScript("init.sql");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PostgresToolInvocationRepository toolInvocationRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    private String conversationId;
    private String turnId;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID().toString();
        turnId = UUID.randomUUID().toString();
        conversationRepository.create(conversationId, "chat", "test", "start");
    }

    @Test
    void pendingCallIsNotReportedAsCompleted() {
        toolInvocationRepository.recordCallStarting(conversationId, turnId, "lookup_order", "{\"order_id\":\"123\"}");

        Optional<String> result = toolInvocationRepository.getResultIfCompleted(conversationId, turnId, "lookup_order");

        assertTrue(result.isEmpty());
    }

    @Test
    void finishedCallReturnsResult() {
        toolInvocationRepository.recordCallStarting(conversationId, turnId, "lookup_order", "{\"order_id\":\"123\"}");
        toolInvocationRepository.recordCallFinished(conversationId, turnId, "lookup_order", "{\"status\":\"delivered\"}", "executed");

        Optional<String> result = toolInvocationRepository.getResultIfCompleted(conversationId, turnId, "lookup_order");

        assertTrue(result.isPresent());
        assertEquals("{\"status\":\"delivered\"}", result.get());
    }
}