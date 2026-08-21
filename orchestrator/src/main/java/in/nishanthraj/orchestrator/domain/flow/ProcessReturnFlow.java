package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.orchestration.Flow;
import in.nishanthraj.orchestrator.domain.orchestration.NodeHandler;
import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

public class ProcessReturnFlow implements Flow {

    private static final double RETURN_APPROVAL_THRESHOLD = 10.00;

    private final ConversationRepository conversationRepository;
    private final SlotRepository slotRepository;
    private final ToolInvocationRepository toolInvocationRepository;
    private final LlmClient llmClient;
    private final OrderServiceClient orderServiceClient;
    private final ObjectMapper objectMapper;
    private final OrderLookupHelper orderLookupHelper;
    private final Map<String, NodeHandler> nodes;


    public ProcessReturnFlow(ConversationRepository conversationRepository,
                             SlotRepository slotRepository,
                             ToolInvocationRepository toolInvocationRepository,
                             LlmClient llmClient,
                             OrderServiceClient orderServiceClient,
                             ObjectMapper objectMapper,
                             OrderLookupHelper orderLookupHelper) {
        this.conversationRepository = conversationRepository;
        this.slotRepository = slotRepository;
        this.toolInvocationRepository = toolInvocationRepository;
        this.llmClient = llmClient;
        this.orderServiceClient = orderServiceClient;
        this.objectMapper = objectMapper;
        this.orderLookupHelper = orderLookupHelper;
        this.nodes = Map.of(
                "collect_order_id", this::handleCollectOrderId,
                "collect_item", this::handleCollectItem,
                "collect_return_reason", this::handleCollectReturnReason,
                "check_threshold", this::handleCheckThreshold,
                "auto_process", this::handleAutoProcess,
                "escalate_to_agent", this::handleEscalateToAgent
        );
    }

    @Override
    public String flowType() {
        return "process_return";
    }

    @Override
    public NodeHandler handlerFor(String nodeName) {
        NodeHandler handler = nodes.get(nodeName);

        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for node:" + nodeName);
        }
        return handler;
    }

    private String handleCollectOrderId(String conversationId, String turnId, String input) {
        String prompt = "Extract the order number from this message. Respond with ONLY the order number, nothing else. If no order number is present, respond with exactly: NONE\n\nMessage: " + input;
        String extracted = llmClient.complete(prompt);

        if (extracted.equals("NONE")) {
            return "I didn't catch an order number — could you share it again?";
        }

        slotRepository.saveSlot(conversationId, "order_id", extracted);
        conversationRepository.updateCurrentNode(conversationId, "collect_item");
        return "Got it. Which item would you like to return?";
    }

    private String handleCollectItem(String conversationId, String turnId, String input) {
        Optional<String> orderIdSlot = slotRepository.getSlot(conversationId, "order_id");
        if (orderIdSlot.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "order_id");
            return "Something went wrong tracking your order. Let me connect you with a human agent.";
        }

        Optional<String> resultJson = orderLookupHelper.lookupOrder(conversationId, turnId, orderIdSlot.get());
        if (resultJson.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I couldn't find an order with that number.";
        }
        slotRepository.saveSlot(conversationId, "order_details_json", resultJson.get());

        OrderServiceClient.OrderDetails orderDetails = objectMapper.readValue(resultJson.get(), OrderServiceClient.OrderDetails.class);

        StringBuilder itemList = new StringBuilder();
        for (OrderServiceClient.OrderLine line : orderDetails.orderLines()) {
            itemList.append("- ").append(line.description()).append("\n");
        }

        String prompt = "The user's order contains these items:\n" + itemList +
                "\nThe user said: \"" + input + "\"\n" +
                "Which item description from the list above are they asking about? Respond with ONLY the exact item description from the list, nothing else. If none match, respond with exactly: NONE";

        String matched = llmClient.complete(prompt);
        if (matched.equals("NONE")) {
            return "I couldn't match that to an item in your order — could you describe it differently?";
        }

        slotRepository.saveSlot(conversationId, "matched_item_description", matched);
        conversationRepository.updateCurrentNode(conversationId, "collect_return_reason");
        return "Got it. What's the reason for the return?";
    }

    private String handleCollectReturnReason(String conversationId, String turnId, String input) {
        slotRepository.saveSlot(conversationId, "return_reason", input);
        conversationRepository.updateCurrentNode(conversationId, "check_threshold");
        return "Thanks, let me check on that for you.";
    }

    private String handleCheckThreshold(String conversationId, String turnId, String input) {
        Optional<String> matchedDescription = slotRepository.getSlot(conversationId, "matched_item_description");
        Optional<String> orderResultJson = slotRepository.getSlot(conversationId, "order_details_json");

        if (matchedDescription.isEmpty() || orderResultJson.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "Something went wrong tracking that item. Let me connect you with a human agent.";
        }

        OrderServiceClient.OrderDetails orderDetails = objectMapper.readValue(orderResultJson.get(), OrderServiceClient.OrderDetails.class);
        Optional<OrderServiceClient.OrderLine> foundLine = orderLookupHelper.findMatchingLine(orderDetails, matchedDescription.get());

        if (foundLine.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I lost track of which item you meant. Let me connect you with a human agent.";
        }

        slotRepository.saveSlot(conversationId, "matched_item_price", String.valueOf(foundLine.get().unitPrice()));

        if (foundLine.get().unitPrice() <= RETURN_APPROVAL_THRESHOLD) {
            conversationRepository.updateCurrentNode(conversationId, "auto_process");
        } else {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
        }

        // both branches route through their own node next turn; this response is neutral
        return foundLine.get().unitPrice() <= RETURN_APPROVAL_THRESHOLD
                ? "Let me process that for you."
                : "This return needs approval from a human agent — connecting you now.";
    }

    private String handleAutoProcess(String conversationId, String turnId, String input) {
        Optional<String> matchedDescription = slotRepository.getSlot(conversationId, "matched_item_description");
        Optional<String> matchedPrice = slotRepository.getSlot(conversationId, "matched_item_price");
        Optional<String> returnReason = slotRepository.getSlot(conversationId, "return_reason");

        if (matchedDescription.isEmpty() || matchedPrice.isEmpty() || returnReason.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "Something went wrong processing your return. Let me connect you with a human agent.";
        }

        return "Your return for " + matchedDescription.get() + " ($" + matchedPrice.get()
                + ") has been processed. Reason: " + returnReason.get() + ". You'll receive a refund confirmation shortly.";
    }

    private String handleEscalateToAgent(String conversationId, String turnId, String input) {
        conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
        return "I've flagged this for a human agent to review. They'll follow up with you shortly.";
    }
}
