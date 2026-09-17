package in.nishanthraj.orchestrator.domain.orchestration;

import in.nishanthraj.orchestrator.domain.orchestration.Flow;
import in.nishanthraj.orchestrator.domain.orchestration.GraphExecutor;
import in.nishanthraj.orchestrator.domain.orchestration.NodeHandler;
import in.nishanthraj.orchestrator.domain.port.ConversationRepository;
import in.nishanthraj.orchestrator.domain.port.InMemoryConversationRepository;
import in.nishanthraj.orchestrator.domain.port.TurnRepository;
import in.nishanthraj.orchestrator.domain.shared.InputBoundaryValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphExecutorTurnIdTest {

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
    void eachStepInsertsATurnWithANewTurnId() {
        TurnRepository turnRepository = mock(TurnRepository.class);
        ConversationRepository conversationRepository = new InMemoryConversationRepository();
        InputBoundaryValidator inputBoundaryValidator = mock(InputBoundaryValidator.class);

        Map<String, Flow> flows = Map.of("intent_classification", trivialFlow());

        GraphExecutor graphExecutor = new GraphExecutor(
                conversationRepository, turnRepository, flows, inputBoundaryValidator
        );

        String conversationId = UUID.randomUUID().toString();

        graphExecutor.step(conversationId, "Where is my order?");
        graphExecutor.step(conversationId, "1002");

        ArgumentCaptor<String> turnIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(turnRepository, atLeast(2)).insertTurn(eq(conversationId), turnIdCaptor.capture(), any(), any());

        List<String> turnIds = turnIdCaptor.getAllValues();
        assertThat(turnIds.get(0)).isNotEqualTo(turnIds.get(1));
    }

    @Test
    void eachExternalStepGetsADistinctUserTurnId() {
        TurnRepository turnRepository = mock(TurnRepository.class);
        ConversationRepository conversationRepository = new InMemoryConversationRepository();
        InputBoundaryValidator inputBoundaryValidator = mock(InputBoundaryValidator.class);
        Map<String, Flow> flows = Map.of("intent_classification", trivialFlow());

        GraphExecutor graphExecutor = new GraphExecutor(
                conversationRepository, turnRepository, flows, inputBoundaryValidator);

        String conversationId = UUID.randomUUID().toString();

        graphExecutor.step(conversationId, "Where is my order?");
        graphExecutor.step(conversationId, "1002");

        ArgumentCaptor<String> speakerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> turnIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(turnRepository, times(4)).insertTurn(eq(conversationId), turnIdCaptor.capture(), speakerCaptor.capture(), any());

        List<String> userTurnIds = new ArrayList<>();
        for (int i = 0; i < speakerCaptor.getAllValues().size(); i++) {
            if (speakerCaptor.getAllValues().get(i).equals("user")) {
                userTurnIds.add(turnIdCaptor.getAllValues().get(i));
            }
        }

        assertThat(userTurnIds).hasSize(2);
        assertThat(userTurnIds.get(0)).isNotEqualTo(userTurnIds.get(1)); // the actual invariant
        assertThat(turnRepository).isNotNull(); // sanity — real assertion above is the point
    }
}