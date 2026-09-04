package in.nishanthraj.orchestrator.domain.orchestration;

import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.InputBoundaryValidator;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GraphExecutorTest {
    private Flow trivialFlow() {
        return new Flow() {
            @Override
            public String flowType() {
                return "intent_classification";
            }
            @Override
            public NodeHandler handlerFor(String nodeName) {
                return (conversationId, turnId, input) -> "Got it, thanks!";
            }

            @Override
            public boolean nodeConsumesInput(String nodeName) {
                return false;
            }

            @Override
            public String describeNode(String nodeName) {
                return "a test node";
            }
        };
    }

    @Test
    void newConversationGetsCreatedAndDispatchesToFlow() {
        ConversationRepository repo = new InMemoryConversationRepository();
        TurnRepository turnRepo = new InMemoryTurnRepository();
        Map<String, Flow> flows = Map.of("intent_classification", trivialFlow());
        InputBoundaryValidator inputBoundaryValidator = new InputBoundaryValidator(new StubLlmClient("unused"));
        GraphExecutor executor = new GraphExecutor(repo, turnRepo, flows, inputBoundaryValidator);
        String result = executor.step("brand-new-conversation-id", "hello");
        assertEquals("Got it, thanks!", result);
        assertEquals("intent_classification", repo.getFlowType("brand-new-conversation-id"));
    }
}