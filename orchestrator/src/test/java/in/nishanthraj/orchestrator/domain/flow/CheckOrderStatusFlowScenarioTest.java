package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class CheckOrderStatusFlowScenarioTest {

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

    private CheckOrderStatusFlow buildFlow(ConversationRepository conversationRepository,
                                           SlotRepository slotRepository,
                                           ToolInvocationRepository toolInvocationRepository,
                                           OrderServiceClient orderServiceClient,
                                           ObjectMapper objectMapper,
                                           LlmClient llmClient) {
        OrderLookupHelper orderLookupHelper = new OrderLookupHelper(orderServiceClient, toolInvocationRepository, objectMapper);
        return new CheckOrderStatusFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, orderServiceClient, orderLookupHelper, objectMapper
        );
    }

    // --- handleCollectDetails ---

    @Test
    void collectDetails_orderIdPresent_transitionsToLookupOrder() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        // 1: extraction  2: phraseNaturally (transitioning)
        QueuedLlmClient llmClient = new QueuedLlmClient("1001", "Let me look that up.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "cd-order-present";
        conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");

        String response = flow.handlerFor("collect_order_id").handle(conversationId, "t1", "check order 1001");

        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));
        assertEquals("1001", slotRepository.getSlot(conversationId, "order_id").orElseThrow());
        assertFalse(response.isBlank());
    }

    @Test
    void collectDetails_noOrderId_staysAndAsksAgain() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        // 1: extraction (NONE)  2: phraseNaturally (asking for order number)
        QueuedLlmClient llmClient = new QueuedLlmClient("NONE", "Could you share your order number?");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "cd-no-order";
        conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");

        String response = flow.handlerFor("collect_order_id").handle(conversationId, "t1", "hi there");

        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));
        assertTrue(slotRepository.getSlot(conversationId, "order_id").isEmpty());
        assertFalse(response.isBlank());
    }

    @Test
    void collectDetails_orderIdAlreadyKnown_doesNotOverwriteOnSubsequentTurn() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        // pre-seed the slot as if a prior turn already set it
        slotRepository.saveSlot("cd-already-known", "order_id", "1001");

        // extraction returns something else entirely — should be ignored since existingOrderId is present
        QueuedLlmClient llmClient = new QueuedLlmClient("9999", "Let me look that up.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "cd-already-known";
        conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");

        flow.handlerFor("collect_order_id").handle(conversationId, "t1", "some unrelated follow-up");

        assertEquals("1001", slotRepository.getSlot(conversationId, "order_id").orElseThrow());
    }

    // --- handleLookupOrder ---

    @Test
    void lookupOrder_success_transitionsToRespondWithDetails() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        orderServiceClient.seed("1001", new OrderServiceClient.OrderDetails(
                "1001", "created", List.of(new OrderServiceClient.OrderLine("item-1", "Running Shoes", 59.99))
        ));
        slotRepository.saveSlot("lo-success", "order_id", "1001");

        QueuedLlmClient llmClient = new QueuedLlmClient("Found your order.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "lo-success";
        conversationRepository.create(conversationId, "chat", "check_order_status", "lookup_order");

        String response = flow.handlerFor("lookup_order").handle(conversationId, "t1", "");

        assertEquals("respond_with_details", conversationRepository.getCurrentNode(conversationId));
        assertTrue(slotRepository.getSlot(conversationId, "order_details_json").isPresent());
        assertFalse(response.isBlank());
    }

    @Test
    void lookupOrder_orderNotFound_escalates() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        // deliberately NOT seeded — lookup will return empty
        slotRepository.saveSlot("lo-not-found", "order_id", "9999");

        QueuedLlmClient llmClient = new QueuedLlmClient(); // no LLM call expected on this branch

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "lo-not-found";
        conversationRepository.create(conversationId, "chat", "check_order_status", "lookup_order");

        String response = flow.handlerFor("lookup_order").handle(conversationId, "t1", "");

        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.toLowerCase().contains("couldn't find"));
    }

    @Test
    void lookupOrder_missingOrderIdSlot_escalatesDefensively() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        // order_id slot never set — simulates a corrupted/unexpected state
        QueuedLlmClient llmClient = new QueuedLlmClient();

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "lo-missing-slot";
        conversationRepository.create(conversationId, "chat", "check_order_status", "lookup_order");

        String response = flow.handlerFor("lookup_order").handle(conversationId, "t1", "");

        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertFalse(response.isBlank());
    }

    // --- handleRespondWithDetails ---

    @Test
    void respondWithDetails_openEndedQuestion_listsAllItems() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        OrderServiceClient.OrderDetails orderDetails = new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(
                        new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99),
                        new OrderServiceClient.OrderLine("item-2", "Running Shoes", 59.99)
                )
        );
        slotRepository.saveSlot("rd-open-ended", "order_details_json", objectMapper.writeValueAsString(orderDetails));

        QueuedLlmClient llmClient = new QueuedLlmClient(
                "Order 1001 contains a Blue T-Shirt ($19.99) and Running Shoes ($59.99)."
        );

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "rd-open-ended";
        conversationRepository.create(conversationId, "chat", "check_order_status", "respond_with_details");

        String response = flow.handlerFor("respond_with_details").handle(conversationId, "t1", "what items are in there?");

        assertEquals("intent_classification", conversationRepository.getFlowType(conversationId));
        assertEquals("classify", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.contains("Blue T-Shirt"));
        assertTrue(response.contains("Running Shoes"));
    }

    @Test
    void respondWithDetails_specificItemQuestion_answersAboutThatItemOnly() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        OrderServiceClient.OrderDetails orderDetails = new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(
                        new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99),
                        new OrderServiceClient.OrderLine("item-2", "Running Shoes", 59.99)
                )
        );
        slotRepository.saveSlot("rd-specific-item", "order_details_json", objectMapper.writeValueAsString(orderDetails));

        QueuedLlmClient llmClient = new QueuedLlmClient("The Running Shoes are $59.99.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "rd-specific-item";
        conversationRepository.create(conversationId, "chat", "check_order_status", "respond_with_details");

        String response = flow.handlerFor("respond_with_details").handle(conversationId, "t1", "how much are the running shoes?");

        assertTrue(response.contains("59.99"));
        assertEquals("intent_classification", conversationRepository.getFlowType(conversationId));
    }

    @Test
    void respondWithDetails_missingOrderDetails_escalates() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        // order_details_json never set
        QueuedLlmClient llmClient = new QueuedLlmClient(); // no LLM call expected

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient);

        String conversationId = "rd-missing-details";
        conversationRepository.create(conversationId, "chat", "check_order_status", "respond_with_details");

        String response = flow.handlerFor("respond_with_details").handle(conversationId, "t1", "what items are there?");

        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertFalse(response.isBlank());
    }
}