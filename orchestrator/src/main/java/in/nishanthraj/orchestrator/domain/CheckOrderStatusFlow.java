package in.nishanthraj.orchestrator.domain;



import java.util.Map;

public class CheckOrderStatusFlow implements  Flow{

    private final ConversationRepository conversationRepository;
    private final SlotRepository slotRepository;
    private final ToolInvocationRepository toolInvocationRepository;
    private final Map<String, NodeHandler> nodes;

    public CheckOrderStatusFlow(ConversationRepository conversationRepository,
                                SlotRepository slotRepository,
                                ToolInvocationRepository toolInvocationRepository) {
        this.conversationRepository = conversationRepository;
        this.slotRepository = slotRepository;
        this.toolInvocationRepository = toolInvocationRepository;
        this.nodes = Map.of(
                "collect_order_id", this::handleCollectOrderId,
                "lookup_order", this::handleLookupOrder,
                "check_order_found", this::handleCheckOrderFound,
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
        if(handler == null) {
            throw new IllegalArgumentException("No handler registered for node: " + nodeName);
        }
        return handler;
    }

    // --- TODO: implement each of these yourself ---

    private String handleCollectOrderId(String conversationId, String input) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    private String handleLookupOrder(String conversationId, String input) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    private String handleCheckOrderFound(String conversationId, String input) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    private String handleEscalateToAgent(String conversationId, String input) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    private String handleCollectItem(String conversationId, String input) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    private String handleMatchItem(String conversationId, String input) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    private String handleRespondWithDetails(String conversationId, String input) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
