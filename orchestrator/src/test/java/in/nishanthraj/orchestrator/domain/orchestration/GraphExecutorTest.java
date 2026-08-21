package in.nishanthraj.orchestrator.domain.orchestration;

import in.nishanthraj.orchestrator.domain.port.InMemoryConversationRepository;
import in.nishanthraj.orchestrator.domain.port.InMemoryTurnRepository;
import in.nishanthraj.orchestrator.domain.port.ConversationRepository;
import in.nishanthraj.orchestrator.domain.port.TurnRepository;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GraphExecutorTest {

    private Flow trivialFlow() {
        return new Flow() {
            @Override
            public String flowType() {
                return "check_order_status";
            }

            @Override
            public NodeHandler handlerFor(String nodeName) {
                return (conversationId, turnId, input) -> "Got it, thanks!";
            }
        };
    }

    @Test
    void newConversationGetsCreatedAndDispatchesToFlow() {
        ConversationRepository repo = new InMemoryConversationRepository();
        TurnRepository turnRepo = new InMemoryTurnRepository();
        Map<String, Flow> flows = Map.of("check_order_status", trivialFlow());
        GraphExecutor executor = new GraphExecutor(repo, turnRepo, flows);

        String result = executor.step("brand-new-conversation-id","hello");

        assertEquals("Got it, thanks!", result);
        assertEquals("check_order_status", repo.getFlowType("brand-new-conversation-id"));
    }
}