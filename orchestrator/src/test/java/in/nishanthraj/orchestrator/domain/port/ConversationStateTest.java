package in.nishanthraj.orchestrator.domain.port;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConversationStateTest {

    @Test
    void getOrCreate_newConversation_startsAtVersionOneWithNoIntentOrFocus() {
        InMemoryConversationStateRepository repository = new InMemoryConversationStateRepository();

        ConversationState state = repository.getOrCreate("conv-1");

        assertEquals("conv-1", state.conversationId());
        assertNull(state.activeIntent());
        assertTrue(state.activeFocus().isEmpty());
        assertEquals(1, state.version());
    }

    @Test
    void applyUpdate_setsIntentFocusAndEntityAtomically_incrementsVersionOnce() {
        InMemoryConversationStateRepository repository = new InMemoryConversationStateRepository();
        repository.getOrCreate("conv-2");

        EntityReference order1001 = new EntityReference("ORDER", "1001");

        ConversationStateUpdate update = new ConversationStateUpdate(
                Optional.of("CHECK_ORDER_STATUS"),
                Optional.of(order1001),
                List.of(order1001)
        );

        ConversationState updated = repository.applyUpdate("conv-2", update, 1);

        assertEquals("CHECK_ORDER_STATUS", updated.activeIntent());
        assertEquals(Optional.of(order1001), updated.activeFocus());
        assertEquals(2, updated.version());

        List<EntityReference> orders = repository.getEntities("conv-2", "ORDER");
        assertEquals(1, orders.size());
        assertEquals(order1001, orders.get(0));
    }

    @Test
    void applyUpdate_partialPatch_leavesUnspecifiedFieldsUnchanged() {
        InMemoryConversationStateRepository repository = new InMemoryConversationStateRepository();
        repository.getOrCreate("conv-3");

        EntityReference order1001 = new EntityReference("ORDER", "1001");
        repository.applyUpdate("conv-3",
                new ConversationStateUpdate(Optional.of("CHECK_ORDER_STATUS"), Optional.of(order1001), List.of(order1001)),
                1);

        // second update only adds a new entity — intent and focus should survive untouched
        EntityReference order1004 = new EntityReference("ORDER", "1004");
        ConversationState afterSecondUpdate = repository.applyUpdate("conv-3",
                new ConversationStateUpdate(Optional.empty(), Optional.empty(), List.of(order1004)),
                2);

        assertEquals("CHECK_ORDER_STATUS", afterSecondUpdate.activeIntent());
        assertEquals(Optional.of(order1001), afterSecondUpdate.activeFocus());
        assertEquals(3, afterSecondUpdate.version());
        assertEquals(2, repository.getEntities("conv-3", "ORDER").size());
    }

    @Test
    void applyUpdate_staleVersion_throwsOptimisticLockException() {
        InMemoryConversationStateRepository repository = new InMemoryConversationStateRepository();
        repository.getOrCreate("conv-4");

        repository.applyUpdate("conv-4",
                new ConversationStateUpdate(Optional.of("CHECK_ORDER_STATUS"), Optional.empty(), List.of()),
                1);

        // trying to apply another update using the now-stale version=1 should fail
        assertThrows(OptimisticLockException.class, () ->
                repository.applyUpdate("conv-4",
                        new ConversationStateUpdate(Optional.of("PROCESS_RETURN"), Optional.empty(), List.of()),
                        1)
        );
    }

    @Test
    void addingTheSameEntityTwice_isIdempotent() {
        InMemoryConversationStateRepository repository = new InMemoryConversationStateRepository();
        repository.getOrCreate("conv-5");

        EntityReference order1001 = new EntityReference("ORDER", "1001");

        repository.applyUpdate("conv-5",
                new ConversationStateUpdate(Optional.empty(), Optional.empty(), List.of(order1001)), 1);
        repository.applyUpdate("conv-5",
                new ConversationStateUpdate(Optional.empty(), Optional.empty(), List.of(order1001)), 2);

        assertEquals(1, repository.getEntities("conv-5", "ORDER").size());
    }
}