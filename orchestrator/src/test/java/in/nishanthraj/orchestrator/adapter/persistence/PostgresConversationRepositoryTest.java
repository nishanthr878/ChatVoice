package in.nishanthraj.orchestrator.adapter.persistence;

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
public class PostgresConversationRepositoryTest {

    private String conversationId;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine").withInitScript("init.sql");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PostgresConversationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUP() {
        conversationId = UUID.randomUUID().toString();
    }

    @Test
    void containerStartsWithSchema() {
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'",
                Integer.class
        );
        assertEquals(4, tableCount);
    }

    @Test
    void testCreateAndExists() {
        repository.create(conversationId, "testChannel", "testFlow", "initialNode");
        boolean exists = repository.exists(conversationId);
        assertEquals(true, exists);
    }

    @Test
    void testUpdateCurrentNode() {
        repository.create(conversationId, "testChannel", "testFlow", "initialNode");
        repository.updateCurrentNode(conversationId, "updatedNode");
        assertEquals("updatedNode", repository.getCurrentNode(conversationId));
    }

    @Test
    void testGetCurrentNode() {
        repository.create(conversationId, "testChannel", "testFlow", "initialNode");
        assertEquals("initialNode", repository.getCurrentNode(conversationId));
    }

    @Test
    void testGetFlowType() {
        repository.create(conversationId, "testChannel", "testFlow", "initialNode");
        assertEquals("testFlow", repository.getFlowType(conversationId));
    }



}
