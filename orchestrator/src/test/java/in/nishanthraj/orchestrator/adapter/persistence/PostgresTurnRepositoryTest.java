package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers
class PostgresTurnRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine").withInitScript("init.sql");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PostgresTurnRepository turnRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private String conversationId;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID().toString();
        conversationRepository.create(conversationId, "chat", "test", "start");
    }

    @Test
    void firstTurnGetsSequenceNumberOne() {
        turnRepository.inserTurn(conversationId, "user", "hello");

        Integer count = jdbc.queryForObject(
                "SELECT sequence_number FROM turn WHERE conversation_id = ?::uuid",
                Integer.class, conversationId
        );
        assertEquals(1, count);
    }

    @Test
    void secondTurnGetsSequenceNumberTwo() {
        turnRepository.inserTurn(conversationId, "user", "first");
        turnRepository.inserTurn(conversationId, "agent", "second");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM turn WHERE conversation_id = ?::uuid",
                Integer.class, conversationId
        );
        assertEquals(2, count);
    }
}