package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.orchestration.Flow;
import in.nishanthraj.orchestrator.domain.orchestration.NodeHandler;
import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

public class CheckOrderStatusFlow implements Flow {

    private final ConversationRepository conversationRepository;
    private final SlotRepository slotRepository;
    private final ToolInvocationRepository toolInvocationRepository;
    private final Map<String, NodeHandler> nodes;
    private final LlmClient llmClient;
    private final OrderServiceClient orderServiceClient;
    private final ObjectMapper objectMapper;
    private final OrderLookupHelper orderLookupHelper;

    public CheckOrderStatusFlow(ConversationRepository conversationRepository,
                                SlotRepository slotRepository,
                                ToolInvocationRepository toolInvocationRepository,
                                LlmClient llmClient,
                                OrderServiceClient orderServiceClient,
                                OrderLookupHelper orderLookupHelper,
                                ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.slotRepository = slotRepository;
        this.toolInvocationRepository = toolInvocationRepository;
        this.llmClient = llmClient;
        this.orderServiceClient = orderServiceClient;
        this.orderLookupHelper = orderLookupHelper;
        this.objectMapper = objectMapper;
        this.nodes = Map.of(
                "collect_order_id", this::handleCollectDetails,
                "lookup_order", this::handleLookupOrder,
                "escalate_to_agent", this::handleEscalateToAgent,
                "respond_with_details", this::handleRespondWithDetails
        );
    }

    @Override
    public String flowType() {
        return "check_order_status";
    }

    @Override
    public NodeHandler handlerFor(String nodeName) {
        NodeHandler handler = nodes.get(nodeName);
        if (handler == null) {
            throw new IllegalStateException("No handler registered for node: " + nodeName);
        }
        return handler;
    }

    @Override
    public boolean nodeConsumesInput(String nodeName) {
        return nodeName.equals("collect_order_id") || nodeName.equals("respond_with_details");
    }

    @Override
    public String describeNode(String nodeName) {
        return "checking an order's status — the assistant needs the order number and, once found, can answer questions about it";
    }

    private String phraseNaturally(String instruction) {
        String prompt = "You are VA, a friendly order-support assistant. " + instruction
                + " Keep it to one short sentence, no preamble.";
        return llmClient.complete(prompt);
    }

    private String handleLookupOrder(String conversationId, String turnId, String input) {
        Optional<String> orderIdSlot = slotRepository.getSlot(conversationId, "order_id");
        if (orderIdSlot.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I couldn't find an order with that number.";
        }
        String orderId = orderIdSlot.get();

        Optional<String> resultJson = orderLookupHelper.lookupOrder(conversationId, turnId, orderId);
        if (resultJson.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I couldn't find an order with that number.";
        }
        slotRepository.saveSlot(conversationId, "order_details_json", resultJson.get());

        // item was already collected upfront by handleCollectDetails — go straight to the final response,
        // don't ask for it again
        conversationRepository.updateCurrentNode(conversationId, "respond_with_details");
        return phraseNaturally("Let the user know you found their order and are pulling up the item details now, briefly.");
    }

    private String handleEscalateToAgent(String conversationId, String turnId, String input) {
        conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
        return "I wasn't able to find that. Let me connect you with a human agent who can help.";
    }

    private String handleCollectDetails(String conversationId, String turnId, String input) {
        Optional<String> existingOrderId = slotRepository.getSlot(conversationId, "order_id");

        String prompt = "Extract the order number mentioned in this message, if present.\n"
                + "Respond with ONLY the order number, or NONE if not mentioned.\n\n"
                + "Message: " + input;

        String extracted = llmClient.complete(prompt);

        if (existingOrderId.isEmpty() && !extracted.equals("NONE")) {
            slotRepository.saveSlot(conversationId, "order_id", extracted);
        }

        Optional<String> orderIdSlot = slotRepository.getSlot(conversationId, "order_id");
        if (orderIdSlot.isEmpty()) {
            return phraseNaturally("Ask the user for their order number, in a friendly, brief way.");
        }

        conversationRepository.updateCurrentNode(conversationId, "lookup_order");
        return phraseNaturally("Let the user know you're looking up their order now, briefly.");
    }

    private String handleRespondWithDetails(String conversationId, String turnId, String input) {
        Optional<String> currentOrderId = slotRepository.getSlot(conversationId, "order_id");

        String checkPrompt = "The user was just discussing order " + currentOrderId.orElse("unknown") + ".\n"
                + "Their message: \"" + input + "\"\n"
                + "Are they now asking about a DIFFERENT order number? If so, respond with ONLY that order number. "
                + "If not, respond with exactly: SAME";

        String possibleNewOrderId = llmClient.complete(checkPrompt);

        if (!possibleNewOrderId.equals("SAME")) {
            slotRepository.saveSlot(conversationId, "order_id", possibleNewOrderId);
            slotRepository.saveSlot(conversationId, "order_details_json", "");
            conversationRepository.updateCurrentNode(conversationId, "lookup_order");
            return phraseNaturally("Let the user know you're pulling up the new order now, briefly. Do not state any specific numbers.");
        }

        Optional<String> orderResultJson = slotRepository.getSlot(conversationId, "order_details_json");
        if (orderResultJson.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I couldn't retrieve your order details. Let me connect you with a human agent.";
        }

        OrderServiceClient.OrderDetails orderDetails = objectMapper.readValue(orderResultJson.get(), OrderServiceClient.OrderDetails.class);

        StringBuilder itemList = new StringBuilder();
        for (OrderServiceClient.OrderLine line : orderDetails.orderLines()) {
            itemList.append("- ").append(line.description()).append(" ($").append(line.unitPrice()).append(")\n");
        }

        String prompt = "You are VA, a friendly order-support assistant.\n"
                + "Order " + orderDetails.orderId() + " (status: " + orderDetails.status() + ") contains:\n" + itemList
                + "\nThe user asked: \"" + input + "\"\n"
                + "Answer using only the order information above. If the question is about a different order or something "
                + "this data doesn't cover, say you don't have that information and ask for the order number they'd like, "
                + "rather than inventing any limitation.";

        String response = llmClient.complete(prompt);

        conversationRepository.updateFlowType(conversationId, "intent_classification");
        conversationRepository.updateCurrentNode(conversationId, "classify");

        return response;
    }
}