package in.nishanthraj.orchestrator.domain.port;

public interface TurnRepository {
    void insertTurn(String conversationId, String turnId, String speaker, String content);
}