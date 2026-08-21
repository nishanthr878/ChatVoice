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
                "collect_order_id", this::handleCollectOrderId,
                "lookup_order", this::handleLookupOrder,
                "escalate_to_agent", this::handleEscalateToAgent,
                "collect_item", this::handleCollectItem,
                "match_item", this::handleMatchItem,
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

    private String handleCollectOrderId(String conversationId, String turnId, String input) {
        String prompt = "Extract the order number from this message. Respond with ONLY the order number, nothing else. If no order number is present, respond with exactly: NONE\n\nMessage: " + input;
        String extracted = llmClient.complete(prompt);

        if (extracted.equals("NONE")) {
            return "I didn't catch an order number — could you share it again?";
        }
        slotRepository.saveSlot(conversationId, "order_id", extracted);
        conversationRepository.updateCurrentNode(conversationId, "lookup_order");
        return "Thanks! I'll check the status of that order for you.";
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

        conversationRepository.updateCurrentNode(conversationId, "collect_item");
        return "Found your order. What item would you like to know about?";
    }



    private String handleEscalateToAgent(String conversationId, String turnId, String input) {
        conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
        return "I wasn't able to find that. Let me connect you with a human agent who can help.";
    }

    private String handleCollectItem(String conversationId, String turnId, String input) {
        Optional<String> orderResultJson = slotRepository.getSlot(conversationId, "order_details_json");
        if (orderResultJson.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "Something went wrong retrieving your order. Let me connect you with a human agent.";
        }

        OrderServiceClient.OrderDetails orderDetails = objectMapper.readValue(orderResultJson.get(), OrderServiceClient.OrderDetails.class);

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
        conversationRepository.updateCurrentNode(conversationId, "match_item");
        return "Got it, let me pull up the details for that item.";
    }

    private String handleMatchItem(String conversationId, String turnId, String input) {
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
        conversationRepository.updateCurrentNode(conversationId, "respond_with_details");
        return "Here's the info on that item: " + foundLine.get().description() + " — $" + foundLine.get().unitPrice();
    }

    private String handleRespondWithDetails(String conversationId, String turnId, String input) {
        conversationRepository.updateCurrentNode(conversationId, "respond_with_details");

        Optional<String> matchedDescription = slotRepository.getSlot(conversationId, "matched_item_description");
        Optional<String> matchedPrice = slotRepository.getSlot(conversationId, "matched_item_price");
        Optional<String> orderResultJson = slotRepository.getSlot(conversationId, "order_details_json");

        if (matchedDescription.isEmpty() || matchedPrice.isEmpty() || orderResultJson.isEmpty()) {
            return "I couldn't retrieve your order details. Let me connect you with a human agent.";
        }

        OrderServiceClient.OrderDetails orderDetails = objectMapper.readValue(orderResultJson.get(), OrderServiceClient.OrderDetails.class);

        return "Order " + orderDetails.orderId() + " (" + orderDetails.status() + "): "
                + matchedDescription.get() + " — $" + matchedPrice.get();
    }
}