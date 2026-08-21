package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.port.ConversationRepository;
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
class PostgresSlotRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine").withInitScript("init.sql");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PostgresSlotRepository slotRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    private String conversationId;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID().toString();
        conversationRepository.create(conversationId, "chat", "test", "start");
    }

    @Test
    void savedSlotCanBeReadBack() {
        slotRepository.saveSlot(conversationId, "order_id", "12345");

        Optional<String> result = slotRepository.getSlot(conversationId, "order_id");

        assertTrue(result.isPresent());
        assertEquals("12345", result.get());
    }

    @Test
    void missingSlotReturnsEmpty() {
        Optional<String> result = slotRepository.getSlot(conversationId, "never_filled");
        assertTrue(result.isEmpty());
    }

    @Test
    void savingSameSlotTwiceOverwrites() {
        slotRepository.saveSlot(conversationId, "order_id", "12345");
        slotRepository.saveSlot(conversationId, "order_id", "99999");

        Optional<String> result = slotRepository.getSlot(conversationId, "order_id");

        assertEquals("99999", result.get());
    }
}