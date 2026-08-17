package in.nishanthraj.orchestrator.domain;

public interface TurnRepository {
    void inserTurn(String conversationId, String speaker, String content);
}
