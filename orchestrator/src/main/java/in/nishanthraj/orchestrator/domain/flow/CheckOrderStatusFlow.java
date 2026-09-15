package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.orchestration.Flow;
import in.nishanthraj.orchestrator.domain.orchestration.NodeHandler;
import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
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
    private final ConversationStateRepository conversationStateRepository;

    public CheckOrderStatusFlow(ConversationRepository conversationRepository,
                                SlotRepository slotRepository,
                                ToolInvocationRepository toolInvocationRepository,
                                LlmClient llmClient,
                                OrderServiceClient orderServiceClient,
                                OrderLookupHelper orderLookupHelper,
                                ObjectMapper objectMapper,
                                ConversationStateRepository conversationStateRepository) {
        this.conversationRepository = conversationRepository;
        this.slotRepository = slotRepository;
        this.toolInvocationRepository = toolInvocationRepository;
        this.llmClient = llmClient;
        this.orderServiceClient = orderServiceClient;
        this.orderLookupHelper = orderLookupHelper;
        this.objectMapper = objectMapper;
        this.conversationStateRepository = conversationStateRepository;
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
    public String describeNode(String nodeName) {
        return "checking an order's status — the assistant needs the order number and, once found, can answer questions about it";
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

    private String phraseNaturally(String instruction) {
        String prompt = "You are VA, a friendly order-support assistant. " + instruction
                + " Keep it to one short sentence, no preamble.";
        return llmClient.complete(prompt);
    }

    private String describeKnownOrders(String conversationId, Optional<EntityReference> activeFocus) {
        List<EntityReference> orders = conversationStateRepository.getEntities(conversationId, "ORDER");
        if (orders.isEmpty()) return "none yet";
        StringBuilder sb = new StringBuilder();
        for (EntityReference order : orders) {
            boolean isFocus = activeFocus.isPresent() && activeFocus.get().equals(order);
            sb.append(order.entityId()).append(isFocus ? " (currently focused)" : "").append(", ");
        }
        return sb.toString();
    }

    private String handleCollectDetails(String conversationId, String turnId, String input) {
        ConversationState state = conversationStateRepository.getOrCreate(conversationId);

        String prompt = "Extract the order number mentioned in this message, if present.\n"
                + "Respond with ONLY the order number, or NONE if not mentioned.\n\n"
                + "Message: " + input;

        String extracted = llmClient.complete(prompt);

        if (state.activeFocus().isEmpty() && extracted.equals("NONE")) {
            return phraseNaturally("Ask the user for their order number, in a friendly, brief way.");
        }

        if (!extracted.equals("NONE")) {
            EntityReference order = new EntityReference("ORDER", extracted);
            conversationStateRepository.applyUpdate(conversationId,
                    new ConversationStateUpdate(Optional.of("CHECK_ORDER_STATUS"), Optional.of(order), List.of(order)),
                    state.version());
        }

        conversationRepository.updateCurrentNode(conversationId, "lookup_order");
        return phraseNaturally("Let the user know you're looking up their order now, briefly.");
    }

    private String handleLookupOrder(String conversationId, String turnId, String input) {
        ConversationState state = conversationStateRepository.getOrCreate(conversationId);
        if (state.activeFocus().isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I couldn't find an order with that number.";
        }
        String orderId = state.activeFocus().get().entityId();

        Optional<String> resultJson = orderLookupHelper.lookupOrder(conversationId, turnId, orderId);
        if (resultJson.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I couldn't find an order with that number.";
        }
        slotRepository.saveSlot(conversationId, "order_details_json", resultJson.get());

        conversationRepository.updateCurrentNode(conversationId, "respond_with_details");
        return phraseNaturally("Let the user know you found their order and are pulling up the item details now, briefly.");
    }

    private String handleEscalateToAgent(String conversationId, String turnId, String input) {
        conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
        return "I wasn't able to find that. Let me connect you with a human agent who can help.";
    }

    private String handleRespondWithDetails(String conversationId, String turnId, String input) {
        ConversationState state = conversationStateRepository.getOrCreate(conversationId);

        String resolvePrompt = "Known orders discussed in this conversation: " + describeKnownOrders(conversationId, state.activeFocus()) + "\n"
                + "The user's latest message: \"" + input + "\"\n"
                + "Which order are they asking about now? If it's the same one currently focused, respond with exactly: CURRENT. "
                + "Otherwise respond with exactly the order number they mean (it may be one already listed above, or a brand new one).";

        String resolved = llmClient.complete(resolvePrompt);

        if (!resolved.equals("CURRENT")) {
            EntityReference newFocus = new EntityReference("ORDER", resolved);
            conversationStateRepository.applyUpdate(conversationId,
                    new ConversationStateUpdate(Optional.empty(), Optional.of(newFocus), List.of(newFocus)),
                    state.version());
            slotRepository.saveSlot(conversationId, "order_details_json", "");
            conversationRepository.updateCurrentNode(conversationId, "lookup_order");
            return phraseNaturally("Let the user know you're pulling up the order now, briefly. Do not state any specific numbers.");
        }

        Optional<String> orderResultJson = slotRepository.getSlot(conversationId, "order_details_json");
        if (orderResultJson.isEmpty() || orderResultJson.get().isEmpty()) {
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