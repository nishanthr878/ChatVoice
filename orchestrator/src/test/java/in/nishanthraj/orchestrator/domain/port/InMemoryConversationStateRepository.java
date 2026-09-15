package in.nishanthraj.orchestrator.domain.port;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryConversationStateRepository implements ConversationStateRepository {

    private final Map<String, ConversationState> states = new ConcurrentHashMap<>();
    private final Map<String, List<EntityReference>> entities = new ConcurrentHashMap<>();

    @Override
    public ConversationState getOrCreate(String conversationId) {
        return states.computeIfAbsent(conversationId,
                id -> new ConversationState(id, null, Optional.empty(), 1));
    }

    @Override
    public ConversationState applyUpdate(String conversationId, ConversationStateUpdate update, int expectedVersion) {
        ConversationState current = states.get(conversationId);
        if (current == null) {
            throw new IllegalStateException("No conversation state exists for: " + conversationId);
        }
        if (current.version() != expectedVersion) {
            throw new OptimisticLockException(
                    "Expected version " + expectedVersion + " but found " + current.version()
                            + " for conversation " + conversationId);
        }

        String newIntent = update.activeIntent().orElse(current.activeIntent());
        Optional<EntityReference> newFocus = update.activeFocus().isPresent()
                ? update.activeFocus()
                : current.activeFocus();

        ConversationState updated = new ConversationState(
                conversationId, newIntent, newFocus, current.version() + 1
        );
        states.put(conversationId, updated);

        for (EntityReference entity : update.newEntities()) {
            List<EntityReference> existing = entities.computeIfAbsent(conversationId, id -> new ArrayList<>());
            if (!existing.contains(entity)) {
                existing.add(entity);
            }
        }

        return updated;
    }

    @Override
    public List<EntityReference> getEntities(String conversationId, String entityType) {
        return entities.getOrDefault(conversationId, List.of()).stream()
                .filter(e -> e.entityType().equals(entityType))
                .toList();
    }
}