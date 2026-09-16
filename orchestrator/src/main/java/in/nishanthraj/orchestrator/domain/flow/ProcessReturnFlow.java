package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.orchestration.Flow;
import in.nishanthraj.orchestrator.domain.orchestration.NodeHandler;
import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ProcessReturnFlow implements Flow {

    private static final double RETURN_APPROVAL_THRESHOLD = 10.00;

    private final ConversationRepository conversationRepository;
    private final SlotRepository slotRepository;
    private final ToolInvocationRepository toolInvocationRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final OrderLookupHelper orderLookupHelper;
    private final ConversationStateRepository conversationStateRepository;
    private final Map<String, NodeHandler> nodes;

    public ProcessReturnFlow(ConversationRepository conversationRepository,
                             SlotRepository slotRepository,
                             ToolInvocationRepository toolInvocationRepository,
                             LlmClient llmClient,
                             ObjectMapper objectMapper,
                             OrderLookupHelper orderLookupHelper,
                             ConversationStateRepository conversationStateRepository) {
        this.conversationRepository = conversationRepository;
        this.slotRepository = slotRepository;
        this.toolInvocationRepository = toolInvocationRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.orderLookupHelper = orderLookupHelper;
        this.conversationStateRepository = conversationStateRepository;
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
            throw new IllegalStateException("No handler registered for node: " + nodeName);
        }
        return handler;
    }

    @Override
    public boolean nodeConsumesInput(String nodeName) {
        return nodeName.equals("collect_order_id");
    }

    @Override
    public String describeNode(String nodeName) {
        return "processing a return — the assistant needs the order number, which item, and the reason for the return";
    }

    private String phraseNaturally(String instruction) {
        String prompt = "You are VA, a friendly order-support assistant. " + instruction
                + " Keep it to one short sentence, no preamble.";
        return llmClient.complete(prompt);
    }

    private String handleCollectDetails(String conversationId, String turnId, String input) {
        ConversationState state = conversationStateRepository.getOrCreate(conversationId);
        Optional<EntityReference> currentFocus = state.activeFocus();

        String prompt = "Current order being discussed: " + currentFocus.map(EntityReference::entityId).orElse("none yet") + "\n"
                + "Message: \"" + input + "\"\n\n"
                + "Extract, in exactly this format, four lines:\n"
                + "ORDER_ID: <order number mentioned, or SAME if continuing the current order, or NONE if no order mentioned at all>\n"
                + "ITEM: <item description mentioned, or NONE>\n"
                + "REASON: <return reason mentioned, or NONE>\n"
                + "IS_SWITCH: <YES if this message asks about a DIFFERENT order than the current one, otherwise NO>";

        String response = llmClient.complete(prompt);
        String[] lines = response.split("\n");
        String orderIdLine = lines.length > 0 ? lines[0].replace("ORDER_ID:", "").trim() : "NONE";
        String itemLine = lines.length > 1 ? lines[1].replace("ITEM:", "").trim() : "NONE";
        String reasonLine = lines.length > 2 ? lines[2].replace("REASON:", "").trim() : "NONE";
        String isSwitch = lines.length > 3 ? lines[3].replace("IS_SWITCH:", "").trim() : "NO";

        if (isSwitch.equals("YES") && !orderIdLine.equals("NONE") && !orderIdLine.equals("SAME")) {
            EntityReference newOrder = new EntityReference("ORDER", orderIdLine);
            conversationStateRepository.applyUpdate(conversationId,
                    new ConversationStateUpdate(Optional.of("PROCESS_RETURN"), Optional.of(newOrder), List.of(newOrder)),
                    state.version());
            slotRepository.saveSlot(conversationId, "matched_item_description", "");
            slotRepository.saveSlot(conversationId, "return_reason", "");
            slotRepository.saveSlot(conversationId, "order_details_json", "");
            currentFocus = Optional.of(newOrder);
        } else if (currentFocus.isEmpty() && !orderIdLine.equals("NONE") && !orderIdLine.equals("SAME")) {
            EntityReference order = new EntityReference("ORDER", orderIdLine);
            conversationStateRepository.applyUpdate(conversationId,
                    new ConversationStateUpdate(Optional.of("PROCESS_RETURN"), Optional.of(order), List.of(order)),
                    state.version());
            currentFocus = Optional.of(order);
        }

        if (!itemLine.equals("NONE")) {
            slotRepository.saveSlot(conversationId, "matched_item_description", itemLine);
        }
        if (!reasonLine.equals("NONE")) {
            slotRepository.saveSlot(conversationId, "return_reason", reasonLine);
        }

        if (currentFocus.isEmpty()) {
            return phraseNaturally("Ask the user for their order number, in a friendly, brief way.");
        }

        Optional<String> itemSlot = slotRepository.getSlot(conversationId, "matched_item_description");
        if (itemSlot.isEmpty() || itemSlot.get().isEmpty()) {
            return phraseNaturally("Briefly acknowledge you have their order number, without stating any specific numbers, then ask which item they'd like to return.");
        }

        Optional<String> reasonSlot = slotRepository.getSlot(conversationId, "return_reason");
        if (reasonSlot.isEmpty() || reasonSlot.get().isEmpty()) {
            return phraseNaturally("Acknowledge the item briefly, without stating any specific numbers or names, then ask why they'd like to return it.");
        }

        conversationRepository.updateCurrentNode(conversationId, "lookup_order");
        return phraseNaturally("Let the user know you're pulling up the order details now, briefly. Do not state any specific numbers.");
    }

    private String handleLookupOrder(String conversationId, String turnId, String input) {
        ConversationState state = conversationStateRepository.getOrCreate(conversationId);
        if (state.activeFocus().isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "Something went wrong tracking your order. Let me connect you with a human agent.";
        }
        String orderId = state.activeFocus().get().entityId();

        Optional<String> resultJson = orderLookupHelper.lookupOrder(conversationId, turnId, orderId);
        if (resultJson.isEmpty()) {
            conversationRepository.updateCurrentNode(conversationId, "escalate_to_agent");
            return "I couldn't find an order with that number.";
        }
        slotRepository.saveSlot(conversationId, "order_details_json", resultJson.get());

        conversationRepository.updateCurrentNode(conversationId, "check_threshold");
        return phraseNaturally("Let the user know you're checking on that for them, briefly. Do not state any specific numbers.");
    }

    private String handleCheckThreshold(String conversationId, String turnId, String input) {
        Optional<String> matchedDescription = slotRepository.getSlot(conversationId, "matched_item_description");
        Optional<String> orderResultJson = slotRepository.getSlot(conversationId, "order_details_json");

        if (matchedDescription.isEmpty() || matchedDescription.get().isEmpty()
                || orderResultJson.isEmpty() || orderResultJson.get().isEmpty()) {
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