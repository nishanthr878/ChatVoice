package in.nishanthraj.orchestrator.domain;

public interface TurnRepository {
    void insertTurn(String conversationId, String turnId, String speaker, String content);
}