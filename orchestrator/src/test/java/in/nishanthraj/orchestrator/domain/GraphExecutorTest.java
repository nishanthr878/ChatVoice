package in.nishanthraj.orchestrator.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GraphExecutorTest {

    @Test
    void trivalFlowMovesToConfirmNode() {
        ConversationRepository repo = new InMemoryConversationRepository();
        TurnRepository turnRepo = new InMemoryTurnRepository();
        GraphExecutor executor = new GraphExecutor(repo, turnRepo);

        String result = executor.step("some-conversation-id", "hello");

        assertEquals("confirm", repo.getCurrentNode("some-conversation-id"));
        assertEquals("Got it, thanks!", result);

    }

    @Test
    void newConversationGetsCreatedAndReachesConfirm() {
        ConversationRepository repo = new InMemoryConversationRepository();
        TurnRepository turnRepo = new InMemoryTurnRepository();
        GraphExecutor executor = new GraphExecutor(repo, turnRepo);

        String result = executor.step("brand-new-conversation-id", "hello");

        assertEquals("confirm", repo.getCurrentNode("brand-new-conversation-id"));
        assertEquals("Got it, thanks!", result);
    }
}
