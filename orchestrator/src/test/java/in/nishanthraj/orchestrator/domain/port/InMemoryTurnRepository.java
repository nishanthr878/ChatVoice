package in.nishanthraj.orchestrator.domain.port;

public class InMemoryTurnRepository implements TurnRepository {
    @Override
    public void insertTurn(String conversationId, String turnId, String speaker, String content) {
        // no-op fake, same as before
    }
}