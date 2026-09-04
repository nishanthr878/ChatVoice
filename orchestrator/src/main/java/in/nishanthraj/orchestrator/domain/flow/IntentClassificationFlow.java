package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.orchestration.Flow;
import in.nishanthraj.orchestrator.domain.orchestration.NodeHandler;
import in.nishanthraj.orchestrator.domain.port.ConversationRepository;
import in.nishanthraj.orchestrator.domain.port.LlmClient;

import java.util.Map;

public class IntentClassificationFlow implements Flow {

    private final ConversationRepository conversationRepository;
    private final LlmClient llmClient;
    private final Map<String, NodeHandler> nodes;

    public IntentClassificationFlow(ConversationRepository conversationRepository, LlmClient llmClient) {
        this.conversationRepository = conversationRepository;
        this.llmClient = llmClient;
        this.nodes = Map.of("classify", this::handleClassify);
    }

    @Override
    public String flowType() {
        return "intent_classification";
    }

    @Override
    public NodeHandler handlerFor(String nodeName) {
        NodeHandler handler = nodes.get(nodeName);
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for node: " + nodeName);
        }
        return handler;
    }

    @Override
    public boolean nodeConsumesInput(String nodeName) {
        return false;
    }

    @Override
    public String describeNode(String nodeName) {
        return "classifying the user's intent";
    }

    private String handleClassify(String conversationId, String turnId, String input) {
        String prompt = "Classify the user's message into exactly one of these categories: "
                + "GREETING (a simple greeting like hi, hello, hey, with no other request), "
                + "CHECK_ORDER_STATUS (asking about an order's status or details), "
                + "PROCESS_RETURN (wants to return an item), "
                + "OTHER (anything else). "
                + "Respond with ONLY one of these four words, nothing else.\n\nMessage: " + input;

        String classification = llmClient.complete(prompt);

        return switch (classification) {
            case "GREETING" -> "Hi! I'm VA, how can I help you today?";
            case "CHECK_ORDER_STATUS" -> {
                conversationRepository.updateFlowType(conversationId, "check_order_status");
                conversationRepository.updateCurrentNode(conversationId, "collect_order_id");
                yield "Sure, I can help with that. What's your order number?";
            }
            case "PROCESS_RETURN" -> {
                conversationRepository.updateFlowType(conversationId, "process_return");
                conversationRepository.updateCurrentNode(conversationId, "collect_order_id");
                yield "I can help you start a return. What's your order number?";
            }
            default -> "I'm not able to help with that directly — let me connect you with a human agent.";
        };
    }
}

