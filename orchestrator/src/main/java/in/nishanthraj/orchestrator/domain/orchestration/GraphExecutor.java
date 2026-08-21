package in.nishanthraj.orchestrator.domain.orchestration;

import in.nishanthraj.orchestrator.domain.port.ConversationRepository;
import in.nishanthraj.orchestrator.domain.port.TurnRepository;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
public class GraphExecutor {

    private final ConversationRepository conversationRepository;
    private final TurnRepository turnRepository;
    private final Map<String, Flow> flowsByType;

    public GraphExecutor(ConversationRepository conversationRepository,
                         TurnRepository turnRepository,
                         Map<String, Flow> flowsByType) {
        this.conversationRepository = conversationRepository;
        this.turnRepository = turnRepository;
        this.flowsByType = flowsByType;
    }

    public String step(String conversationId, String input) {
        if (!conversationRepository.exists(conversationId)) {
            conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");
        }

        String userTurnId = UUID.randomUUID().toString();
        turnRepository.insertTurn(conversationId, userTurnId, "user", input);

        String flowType = conversationRepository.getFlowType(conversationId);
        String currentNode = conversationRepository.getCurrentNode(conversationId);

        Flow flow = flowsByType.get(flowType);
        if (flow == null) {
            throw new IllegalArgumentException("No flow registered for flow_type: " + flowType);
        }

        NodeHandler handler = flow.handlerFor(currentNode);
        String response = handler.handle(conversationId, userTurnId, input);

        String agentTurnId = UUID.randomUUID().toString();
        turnRepository.insertTurn(conversationId, agentTurnId, "agent", response);

        return response;
    }
}