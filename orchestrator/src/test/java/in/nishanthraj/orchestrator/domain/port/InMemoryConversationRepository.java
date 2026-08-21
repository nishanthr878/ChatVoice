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
    public String getFlowType(String conversationId) {
        return flowTypes.get(conversationId);
    }

    public static class InMemoryToolInvocationRepository implements ToolInvocationRepository {

        private final Map<String, String> results = new HashMap<>();
        private final Map<String, String> pending = new HashMap<>();

        private String key(String conversationId, String turnId, String toolName) {
            return conversationId + ":" + turnId + ":" + toolName;
        }

        @Override
        public Optional<String> getResultIfCompleted(String conversationId, String turnId, String toolName) {
            return Optional.ofNullable(results.get(key(conversationId, turnId, toolName)));
        }

        @Override
        public void recordCallStarting(String conversationId, String turnId, String toolName, String arguments) {
            pending.put(key(conversationId, turnId, toolName), arguments);
        }

        @Override
        public void recordCallFinished(String conversationId, String turnId, String toolName, String resultJson, String status) {
            results.put(key(conversationId, turnId, toolName), resultJson);
        }
    }
}