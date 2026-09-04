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
                "collect_order_id", this::handleCollectDetails,
                "lookup_order", this::handleLookupOrder,
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

    @Override
    public boolean nodeConsumesInput(String nodeName) {
        return nodeName.equals("collect_order_id");
    }

    private String phraseNaturally(String instruction) {
        String prompt = "You are VA, a friendly order-support assistant. " + instruction
                + " Keep it to one short sentence, no preamble.";
        return llmClient.complete(prompt);
    }

    @Override
    public String describeNode(String nodeName) {
        return "processing a return — the assistant needs the order number, which item, and the reason for the return";
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

    private String handleCollectDetails(String conversationId, String turnId, String input) {
        Optional<String> existingOrderId = slotRepository.getSlot(conversationId, "order_id");

        String prompt = "Extract the order number, item description, and/or return reason mentioned in this message, if present.\n"
                + "Respond in exactly this format, three lines:\n"
                + "ORDER_ID: <the order number, or NONE if not mentioned>\n"
                + "ITEM: <the item description, or NONE if not mentioned>\n"
                + "REASON: <the return reason, or NONE if not mentioned>\n\n"
                + "Message: " + input;

        String response = llmClient.complete(prompt);
        String[] lines = response.split("\n");

        String extractedOrderId = lines.length > 0 ? lines[0].replace("ORDER_ID:", "").trim() : "NONE";
        String extractedItem = lines.length > 1 ? lines[1].replace("ITEM:", "").trim() : "NONE";
        String extractedReason = lines.length > 2 ? lines[2].replace("REASON:", "").trim() : "NONE";

        if (existingOrderId.isEmpty() && !extractedOrderId.equals("NONE")) {
            slotRepository.saveSlot(conversationId, "order_id", extractedOrderId);
        }
        if (!extractedItem.equals("NONE")) {
            slotRepository.saveSlot(conversationId, "matched_item_description", extractedItem);
        }
        if (!extractedReason.equals("NONE")) {
            slotRepository.saveSlot(conversationId, "return_reason", extractedReason);
        }

        Optional<String> orderIdSlot = slotRepository.getSlot(conversationId, "order_id");
        if (orderIdSlot.isEmpty()) {
            return phraseNaturally("Ask the user for their order number, in a friendly, brief way. Do not state any specific numbers.");
        }

        Optional<String> itemSlot = slotRepository.getSlot(conversationId, "matched_item_description");
        if (itemSlot.isEmpty()) {
            return phraseNaturally("Briefly acknowledge you have their order number, without stating any specific numbers, then ask which item they'd like to return.");
        }

        Optional<String> reasonSlot = slotRepository.getSlot(conversationId, "return_reason");
        if (reasonSlot.isEmpty()) {
            return phraseNaturally("Acknowledge the item briefly, without stating any specific numbers or names, then ask why they'd like to return it.");
        }

        conversationRepository.updateCurrentNode(conversationId, "lookup_order");
        return phraseNaturally("Let the user know you're pulling up the order details now, briefly. Do not state any specific numbers.");
    }

    private String handleLookupOrder(String conversationId, String turnId, String input) {
        Optional<String> orderIdSlot = slotRepository.getSlot(conversationId, "order_id");
        if (orderIdSlot.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "Something went wrong tracking your order. Let me connect you with a human agent.";
        }

        Optional<String> resultJson = orderLookupHelper.lookupOrder(conversationId, turnId, orderIdSlot.get());
        if (resultJson.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I couldn't find an order with that number.";
        }
        slotRepository.saveSlot(conversationId, "order_details_json", resultJson.get());

        conversationRepository.updateCurrentNode(conversationId, "check_threshold");
        return "Thanks, let me check on that for you.";
    }

    private String handleAutoProcess(String conversationId, String turnId, String input) {
        Optional<String> matchedDescription = slotRepository.getSlot(conversationId, "matched_item_description");
        Optional<String> matchedPrice = slotRepository.getSlot(conversationId, "matched_item_price");
        Optional<String> returnReason = slotRepository.getSlot(conversationId, "return_reason");

        if (matchedDescription.isEmpty() || matchedPrice.isEmpty() || returnReason.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "Something went wrong processing your return. Let me connect you with a human agent.";
        }

        String response = "Your return for " + matchedDescription.get() + " ($" + matchedPrice.get()
                + ") has been processed. Reason: " + returnReason.get() + ". You'll receive a refund confirmation shortly.";

        conversationRepository.updateFlowType(conversationId, "intent_classification");
        conversationRepository.updateCurrentNode(conversationId, "classify");

        return response;
    }

    private String handleEscalateToAgent(String conversationId, String turnId, String input) {
        conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
        return "I've flagged this for a human agent to review. They'll follow up with you shortly.";
    }
}
