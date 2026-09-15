package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.port.ConversationState;
import in.nishanthraj.orchestrator.domain.port.ConversationStateUpdate;
import in.nishanthraj.orchestrator.domain.port.EntityReference;
import in.nishanthraj.orchestrator.domain.port.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class PostgresConversationStateRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine").withInitScript("init.sql");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PostgresConversationStateRepository repository;

    private String conversationId;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID().toString();
    }

    @Test
    void getOrCreate_newConversation_startsAtVersionOneWithNoIntentOrFocus() {
        ConversationState state = repository.getOrCreate(conversationId);

        assertEquals(conversationId, state.conversationId());
        assertNull(state.activeIntent());
        assertTrue(state.activeFocus().isEmpty());
        assertEquals(1, state.version());
    }

    @Test
    void getOrCreate_calledTwiceForSameId_doesNotDuplicateOrReset() {
        repository.getOrCreate(conversationId);
        repository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.of("CHECK_ORDER_STATUS"), Optional.empty(), List.of()), 1);

        // second getOrCreate on an existing conversation must NOT reset it back to version 1
        ConversationState state = repository.getOrCreate(conversationId);

        assertEquals("CHECK_ORDER_STATUS", state.activeIntent());
        assertEquals(2, state.version());
    }

    @Test
    void applyUpdate_setsIntentFocusAndEntityAtomically_persistsAcrossReads() {
        repository.getOrCreate(conversationId);
        EntityReference order1001 = new EntityReference("ORDER", "1001");

        ConversationState updated = repository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.of("CHECK_ORDER_STATUS"), Optional.of(order1001), List.of(order1001)),
                1);

        assertEquals("CHECK_ORDER_STATUS", updated.activeIntent());
        assertEquals(Optional.of(order1001), updated.activeFocus());
        assertEquals(2, updated.version());

        // re-read fresh from the database, not the in-memory return value, to prove it actually persisted
        ConversationState reread = repository.getOrCreate(conversationId);
        assertEquals("CHECK_ORDER_STATUS", reread.activeIntent());
        assertEquals(Optional.of(order1001), reread.activeFocus());

        List<EntityReference> orders = repository.getEntities(conversationId, "ORDER");
        assertEquals(1, orders.size());
        assertEquals(order1001, orders.get(0));
    }

    @Test
    void applyUpdate_partialPatch_leavesUnspecifiedFieldsUnchanged() {
        repository.getOrCreate(conversationId);
        EntityReference order1001 = new EntityReference("ORDER", "1001");
        repository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.of("CHECK_ORDER_STATUS"), Optional.of(order1001), List.of(order1001)),
                1);

        EntityReference order1004 = new EntityReference("ORDER", "1004");
        ConversationState afterSecondUpdate = repository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.empty(), List.of(order1004)),
                2);

        assertEquals("CHECK_ORDER_STATUS", afterSecondUpdate.activeIntent());
        assertEquals(Optional.of(order1001), afterSecondUpdate.activeFocus());
        assertEquals(3, afterSecondUpdate.version());
        assertEquals(2, repository.getEntities(conversationId, "ORDER").size());
    }

    @Test
    void applyUpdate_staleVersion_throwsOptimisticLockException() {
        repository.getOrCreate(conversationId);
        repository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.of("CHECK_ORDER_STATUS"), Optional.empty(), List.of()), 1);

        assertThrows(OptimisticLockException.class, () ->
                repository.applyUpdate(conversationId,
                        new ConversationStateUpdate(Optional.of("PROCESS_RETURN"), Optional.empty(), List.of()),
                        1) // stale — real current version is now 2
        );
    }

    @Test
    void addingTheSameEntityTwice_isIdempotent() {
        repository.getOrCreate(conversationId);
        EntityReference order1001 = new EntityReference("ORDER", "1001");

        repository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.empty(), List.of(order1001)), 1);
        repository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.empty(), List.of(order1001)), 2);

        assertEquals(1, repository.getEntities(conversationId, "ORDER").size());
    }
}