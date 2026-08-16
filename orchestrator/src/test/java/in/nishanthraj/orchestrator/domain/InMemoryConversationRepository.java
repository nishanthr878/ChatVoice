package in.nishanthraj.orchestrator.domain;

import java.util.HashMap;
import java.util.Map;

public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, String> currentNodes = new HashMap<>();

    @Override
    public String getCurrentNode(String conversationId) {
        return currentNodes.getOrDefault(conversationId, "start");
    }

    @Override
    public void updateCurrentNode(String conversationId, String newNode) {
        currentNodes.put(conversationId, newNode);
    }

    @Override
    public boolean exists(String conversationId) {
        return currentNodes.containsKey(conversationId);
    }

    @Override
    public void create(String conversationId, String channel, String flowType, String initialNode) {
        currentNodes.put(conversationId, initialNode);
    }
}
