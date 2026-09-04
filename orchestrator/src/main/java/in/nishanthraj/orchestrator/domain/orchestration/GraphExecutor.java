package in.nishanthraj.orchestrator.domain.orchestration;

import in.nishanthraj.orchestrator.domain.port.ConversationRepository;
import in.nishanthraj.orchestrator.domain.port.TurnRepository;
import in.nishanthraj.orchestrator.domain.shared.InputBoundaryValidator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class GraphExecutor {

    private static final int MAX_HOPS_PER_TURN = 5;

    private final ConversationRepository conversationRepository;
    private final TurnRepository turnRepository;
    private final Map<String, Flow> flowsByType;
    private final InputBoundaryValidator inputBoundaryValidator;

    public GraphExecutor(ConversationRepository conversationRepository,
                         TurnRepository turnRepository,
                         Map<String, Flow> flowsByType,
                         InputBoundaryValidator inputBoundaryValidator) {
        this.conversationRepository = conversationRepository;
        this.turnRepository = turnRepository;
        this.flowsByType = flowsByType;
        this.inputBoundaryValidator = inputBoundaryValidator;
    }

    public String step(String conversationId, String input) {
        if (!conversationRepository.exists(conversationId)) {
            conversationRepository.create(conversationId, "chat", "intent_classification", "classify");
        }

        String userTurnId = UUID.randomUUID().toString();
        turnRepository.insertTurn(conversationId, userTurnId, "user", input);

        String response = null;

        for (int hop = 0; hop < MAX_HOPS_PER_TURN; hop++) {
            String flowType = conversationRepository.getFlowType(conversationId);

            if (flowType.equals("intent_classification") && hop > 0) {
                break;
            }

            String currentNode = conversationRepository.getCurrentNode(conversationId);
            Flow flow = flowsByType.get(flowType);
            if (flow == null) {
                throw new IllegalArgumentException("No flow registered for flow_type: " + flowType);
            }

            if (!flowType.equals("intent_classification") && flow.nodeConsumesInput(currentNode)) {
                String taskDescription = flow.describeNode(currentNode);
                InputValidationResult validation = inputBoundaryValidator.validate(taskDescription, input);
                if (validation.decision() == InputValidationResult.Decision.SWITCH) {
                    conversationRepository.updateFlowType(conversationId, "intent_classification");
                    conversationRepository.updateCurrentNode(conversationId, "classify");
                    continue;
                }
            }

            response = dispatch(conversationId, userTurnId, input, flowType);

            String nodeAfter = conversationRepository.getCurrentNode(conversationId);
            if (nodeAfter.equals(currentNode)) {
                break;
            }
        }

        String agentTurnId = UUID.randomUUID().toString();
        turnRepository.insertTurn(conversationId, agentTurnId, "agent", response);

        return response;
    }

    private String dispatch(String conversationId, String turnId, String input, String flowType) {
        Flow flow = flowsByType.get(flowType);
        if (flow == null) {
            throw new IllegalArgumentException("No flow registered for flow_type: " + flowType);
        }

        String currentNode = conversationRepository.getCurrentNode(conversationId);
        NodeHandler handler = flow.handlerFor(currentNode);
        return handler.handle(conversationId, turnId, input);
    }
}