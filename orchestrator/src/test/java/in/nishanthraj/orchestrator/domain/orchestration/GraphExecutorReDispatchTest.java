package in.nishanthraj.orchestrator.domain.orchestration;

import in.nishanthraj.orchestrator.domain.flow.CheckOrderStatusFlow;
import in.nishanthraj.orchestrator.domain.flow.IntentClassificationFlow;
import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.InputBoundaryValidator;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class GraphExecutorReDispatchTest {

    private static class QueuedLlmClient implements LlmClient {
        private final Queue<String> responses = new LinkedList<>();
        QueuedLlmClient(String... responsesInOrder) {
            for (String r : responsesInOrder) responses.add(r);
        }
        @Override
        public String complete(String prompt) {
            return responses.poll();
        }
    }

    private Map<String, Flow> buildFlows(ConversationRepository conversationRepository,
                                         SlotRepository slotRepository,
                                         ToolInvocationRepository toolInvocationRepository,
                                         OrderServiceClient orderServiceClient,
                                         ObjectMapper objectMapper,
                                         LlmClient llmClient) {
        OrderLookupHelper orderLookupHelper = new OrderLookupHelper(orderServiceClient, toolInvocationRepository, objectMapper);
        IntentClassificationFlow classificationFlow = new IntentClassificationFlow(conversationRepository, llmClient);
        CheckOrderStatusFlow checkOrderStatusFlow = new CheckOrderStatusFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, orderServiceClient, orderLookupHelper, objectMapper
        );
        return Map.of(
                "intent_classification", classificationFlow,
                "check_order_status", checkOrderStatusFlow
        );
    }

    @Test
    void fullChainCompletesInOneMessage_openEndedQuestion() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemoryTurnRepository turnRepository = new InMemoryTurnRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        orderServiceClient.seed("1001", new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(
                        new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99),
                        new OrderServiceClient.OrderLine("item-2", "Running Shoes", 59.99)
                )
        ));

        // 1: classify -> CHECK_ORDER_STATUS
        // 2: handleCollectDetails extraction (order id only)
        // 3: handleCollectDetails phraseNaturally (-> lookup_order)
        // 4: handleLookupOrder phraseNaturally (-> respond_with_details)
        // 5: handleRespondWithDetails reasoning over full order + raw input
        QueuedLlmClient llmClient = new QueuedLlmClient(
                "CHECK_ORDER_STATUS",
                "CONTINUE",
                "1001",
                "Let me look that up for you.",
                "Found it, one moment.",
                "CONTINUE",
                "Order 1001 contains a Blue T-Shirt ($19.99) and Running Shoes ($59.99)."
        );

        Map<String, Flow> flows = buildFlows(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);
        InputBoundaryValidator inputBoundaryValidator = new InputBoundaryValidator(llmClient);
        GraphExecutor executor = new GraphExecutor(conversationRepository, turnRepository, flows, inputBoundaryValidator);

        String conversationId = "full-chain-open-ended";
        String response = executor.step(conversationId, "check order details for 1001 and let me know what items are present there");

        assertEquals("intent_classification", conversationRepository.getFlowType(conversationId));
        assertEquals("classify", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.contains("Blue T-Shirt"));
        assertTrue(response.contains("Running Shoes"));
    }

    @Test
    void stopsAndWaitsWhenOrderIdIsMissing() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemoryTurnRepository turnRepository = new InMemoryTurnRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        // 1: classify -> CHECK_ORDER_STATUS
        // 2: handleCollectDetails extraction (NONE)
        // 3: handleCollectDetails phraseNaturally (asking for order number) — loop stops, node unchanged
        QueuedLlmClient llmClient = new QueuedLlmClient(
                "CHECK_ORDER_STATUS",
                "CONTINUE",
                "NONE",
                "Could you share your order number?"
        );

        Map<String, Flow> flows = buildFlows(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);
        InputBoundaryValidator inputBoundaryValidator = new InputBoundaryValidator(llmClient);
        GraphExecutor executor = new GraphExecutor(conversationRepository, turnRepository, flows, inputBoundaryValidator);

        String conversationId = "stops-waiting";
        String response = executor.step(conversationId, "I need help with my order");

        assertEquals("check_order_status", conversationRepository.getFlowType(conversationId));
        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));
        assertFalse(response.isBlank());
    }

    @Test
    void orderIdArrivesOnSecondMessage_stillCompletes() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemoryTurnRepository turnRepository = new InMemoryTurnRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        orderServiceClient.seed("1001", new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(new OrderServiceClient.OrderLine("item-1", "Running Shoes", 59.99))
        ));

        // Message 1: classify -> CHECK_ORDER_STATUS, extraction (NONE), phraseNaturally asking for order number
        // Message 2 (flow_type already check_order_status, no re-classify):
        //   extraction (1001), phraseNaturally (-> lookup_order), phraseNaturally (-> respond_with_details),
        //   respond_with_details reasoning
        QueuedLlmClient llmClient = new QueuedLlmClient(
                "CHECK_ORDER_STATUS",
                "CONTINUE",
                "NONE",
                "Could you share your order number?",
                "CONTINUE",
                "1001",
                "Let me look that up.",
                "Found it, one moment.",
                "CONTINUE",
                "Your order 1001 is currently in created status."
        );

        Map<String, Flow> flows = buildFlows(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);
        InputBoundaryValidator inputBoundaryValidator = new InputBoundaryValidator(llmClient);
        GraphExecutor executor = new GraphExecutor(conversationRepository, turnRepository, flows, inputBoundaryValidator);

        String conversationId = "split-turns";
        executor.step(conversationId, "I need help with my order");
        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));

        String secondResponse = executor.step(conversationId, "it's 1001");

        assertEquals("intent_classification", conversationRepository.getFlowType(conversationId));
        assertEquals("classify", conversationRepository.getCurrentNode(conversationId));
        assertTrue(secondResponse.contains("1001"));
    }

    @Test
    void greetingDoesNotTriggerAnyReDispatch() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemoryTurnRepository turnRepository = new InMemoryTurnRepository();

        // Only ONE response queued — a wrongly-firing re-dispatch would exhaust
        // the queue and throw, rather than silently passing.
        QueuedLlmClient llmClient = new QueuedLlmClient("GREETING");

        IntentClassificationFlow classificationFlow = new IntentClassificationFlow(conversationRepository, llmClient);
        Map<String, Flow> flows = Map.of("intent_classification", classificationFlow);

        InputBoundaryValidator inputBoundaryValidator = new InputBoundaryValidator(llmClient);
        GraphExecutor executor = new GraphExecutor(conversationRepository, turnRepository, flows, inputBoundaryValidator);

        String conversationId = "greeting-no-redispatch";
        String response = executor.step(conversationId, "hi");

        assertEquals("intent_classification", conversationRepository.getFlowType(conversationId));
        assertEquals("classify", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.toLowerCase().contains("va") || response.toLowerCase().contains("help"));
    }

    @Test
    void orderNotFound_haltsChainAtEscalation() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemoryTurnRepository turnRepository = new InMemoryTurnRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        // deliberately NOT seeded — order 9999 doesn't exist
        // 1: classify  2: extraction  3: phraseNaturally (-> lookup_order)
        // lookup_order's not-found branch makes no LLM call and stops the loop
        QueuedLlmClient llmClient = new QueuedLlmClient(
                "CHECK_ORDER_STATUS",
                "9999",
                "Let me look that up."
        );

        Map<String, Flow> flows = buildFlows(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);
        InputBoundaryValidator inputBoundaryValidator = new InputBoundaryValidator(llmClient);
        GraphExecutor executor = new GraphExecutor(conversationRepository, turnRepository, flows, inputBoundaryValidator);

        String conversationId = "order-not-found";
        String response = executor.step(conversationId, "check order 9999");

        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.toLowerCase().contains("human agent"));
    }
}