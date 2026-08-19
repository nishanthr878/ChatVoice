package in.nishanthraj.orchestrator.domain;

import in.nishanthraj.orchestrator.adapter.consumer.TurnPayload;

public interface ConversationRepository {
    String getCurrentNode(String conversationId);
    void updateCurrentNode(String conversationId, String newNode);
    boolean exists(String conversationId);
    void create(String conversationId, String channel, String flowType, String initialNode);
    String getFlowType(String conversationId);
}
