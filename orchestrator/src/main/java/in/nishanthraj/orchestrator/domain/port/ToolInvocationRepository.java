package in.nishanthraj.orchestrator.domain.port;


import java.util.Optional;

public interface ToolInvocationRepository {
    Optional<String> getResultIfCompleted(String conversationId, String turnId, String toolName);

    void recordCallStarting(String conversationId, String turnId, String toolName, String arguments);

    void recordCallFinished(String conversationId, String turnId, String toolName, String result, String status);
}
