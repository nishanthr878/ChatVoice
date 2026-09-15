package in.nishanthraj.orchestrator.domain.port;

import java.util.List;

public interface ConversationStateRepository {
    ConversationState getOrCreate(String conversationId);
    ConversationState applyUpdate(String conversationId, ConversationStateUpdate update, int expectedVersion);
    List<EntityReference> getEntities(String conversationId, String entityType);
}
