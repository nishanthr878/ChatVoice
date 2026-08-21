package in.nishanthraj.orchestrator.domain.port;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, String> currentNodes = new HashMap<>();
    private final Map<String, String> flowTypes = new HashMap<>();

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
        flowTypes.put(conversationId, flowType);
    }

    @Override
    public void updateFlowType(String conversationId, String flowType) {
        flowTypes.put(conversationId, flowType);
    }

    @Override
    public String getFlowType(String conversationId) {
        return flowTypes.get(conversationId);
    }
}