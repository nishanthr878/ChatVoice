package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class ProcessReturnFlowScenarioTest {

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

    private ProcessReturnFlow buildFlow(ConversationRepository conversationRepository,
                                        SlotRepository slotRepository,
                                        ToolInvocationRepository toolInvocationRepository,
                                        OrderServiceClient orderServiceClient,
                                        ObjectMapper objectMapper,
                                        LlmClient llmClient,
                                        ConversationStateRepository conversationStateRepository) {
        OrderLookupHelper orderLookupHelper = new OrderLookupHelper(orderServiceClient, toolInvocationRepository, objectMapper);
        return new ProcessReturnFlow(conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, objectMapper, orderLookupHelper, conversationStateRepository);
    }

    @Test
    void collectDetails_allThreeInOneMessage_transitionsToLookupOrder() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        QueuedLlmClient llmClient = new QueuedLlmClient(
                "ORDER_ID: 1001\nITEM: Blue T-Shirt\nREASON: wrong size\nIS_SWITCH: NO",
                "Let me pull that up."
        );

        ProcessReturnFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        String conversationId = "pr-all-in-one";
        conversationRepository.create(conversationId, "chat", "process_return", "collect_order_id");

        String response = flow.handlerFor("collect_order_id").handle(conversationId, "t1", "return order 1001, the blue t-shirt, wrong size");

        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));
        assertEquals(Optional.of(new EntityReference("ORDER", "1001")), conversationStateRepository.getOrCreate(conversationId).activeFocus());
        assertEquals("Blue T-Shirt", slotRepository.getSlot(conversationId, "matched_item_description").orElseThrow());
        assertEquals("wrong size", slotRepository.getSlot(conversationId, "return_reason").orElseThrow());
        assertFalse(response.isBlank());
    }

    @Test
    void collectDetails_orderOnly_asksForItemNext() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        QueuedLlmClient llmClient = new QueuedLlmClient(
                "ORDER_ID: 1001\nITEM: NONE\nREASON: NONE\nIS_SWITCH: NO",
                "Which item would you like to return?"
        );

        ProcessReturnFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        String conversationId = "pr-order-only";
        conversationRepository.create(conversationId, "chat", "process_return", "collect_order_id");

        flow.handlerFor("collect_order_id").handle(conversationId, "t1", "I want to return order 1001");

        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));
        assertEquals(Optional.of(new EntityReference("ORDER", "1001")), conversationStateRepository.getOrCreate(conversationId).activeFocus());
    }

    // --- the real invariant: switching orders invalidates dependent slots, but
    //     new facts in the SAME utterance still land against the new focus ---

    @Test
    void switchingOrderWithNewItemInSameMessage_invalidatesOldSlotsButKeepsNewItem() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        String conversationId = "pr-switch-with-item";
        EntityReference order1001 = new EntityReference("ORDER", "1001");
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.of("PROCESS_RETURN"), Optional.of(order1001), List.of(order1001)), seeded.version());
        slotRepository.saveSlot(conversationId, "matched_item_description", "Blue T-Shirt");
        slotRepository.saveSlot(conversationId, "return_reason", "wrong size");
        slotRepository.saveSlot(conversationId, "order_details_json", "{\"stale\":\"data\"}");

        QueuedLlmClient llmClient = new QueuedLlmClient(
                "ORDER_ID: 1004\nITEM: jacket\nREASON: NONE\nIS_SWITCH: YES",
                "Sure, let's look at that order instead."
        );

        ProcessReturnFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "process_return", "collect_order_id");

        flow.handlerFor("collect_order_id").handle(conversationId, "t2", "actually, return the jacket from order 1004 instead");

        ConversationState afterSwitch = conversationStateRepository.getOrCreate(conversationId);
        assertEquals(Optional.of(new EntityReference("ORDER", "1004")), afterSwitch.activeFocus());
        assertEquals("jacket", slotRepository.getSlot(conversationId, "matched_item_description").orElseThrow());
        assertTrue(slotRepository.getSlot(conversationId, "return_reason").orElseThrow().isEmpty());
        assertTrue(slotRepository.getSlot(conversationId, "order_details_json").orElseThrow().isEmpty());
    }

    @Test
    void switchingOrderWithNoNewInfo_invalidatesAllDependentSlots() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        String conversationId = "pr-switch-no-info";
        EntityReference order1001 = new EntityReference("ORDER", "1001");
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.of("PROCESS_RETURN"), Optional.of(order1001), List.of(order1001)), seeded.version());
        slotRepository.saveSlot(conversationId, "matched_item_description", "Blue T-Shirt");
        slotRepository.saveSlot(conversationId, "return_reason", "wrong size");

        QueuedLlmClient llmClient = new QueuedLlmClient(
                "ORDER_ID: 1004\nITEM: NONE\nREASON: NONE\nIS_SWITCH: YES",
                "Which item would you like to return from that order?"
        );

        ProcessReturnFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "process_return", "collect_order_id");

        flow.handlerFor("collect_order_id").handle(conversationId, "t2", "actually, order 1004 instead");

        ConversationState afterSwitch = conversationStateRepository.getOrCreate(conversationId);
        assertEquals(Optional.of(new EntityReference("ORDER", "1004")), afterSwitch.activeFocus());
        assertTrue(slotRepository.getSlot(conversationId, "matched_item_description").orElseThrow().isEmpty());
        assertTrue(slotRepository.getSlot(conversationId, "return_reason").orElseThrow().isEmpty());
    }

    // --- threshold behavior, unchanged in spirit from before D21, still pure code ---

    @Test
    void checkThreshold_lowValueItem_routesToAutoProcess() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        OrderServiceClient.OrderDetails orderDetails = new OrderServiceClient.OrderDetails(
                "2002", "created", List.of(new OrderServiceClient.OrderLine("item-1", "Phone Case", 8.99))
        );

        String conversationId = "pr-threshold-low";
        slotRepository.saveSlot(conversationId, "matched_item_description", "Phone Case");
        slotRepository.saveSlot(conversationId, "order_details_json", objectMapper.writeValueAsString(orderDetails));

        QueuedLlmClient llmClient = new QueuedLlmClient(); // no LLM call expected — pure deterministic code

        ProcessReturnFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "process_return", "check_threshold");

        flow.handlerFor("check_threshold").handle(conversationId, "t1", "");

        assertEquals("auto_process", conversationRepository.getCurrentNode(conversationId));
        assertEquals("8.99", slotRepository.getSlot(conversationId, "matched_item_price").orElseThrow());
    }

    @Test
    void checkThreshold_highValueItem_routesToEscalation() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        OrderServiceClient.OrderDetails orderDetails = new OrderServiceClient.OrderDetails(
                "1001", "created", List.of(new OrderServiceClient.OrderLine("item-1", "Running Shoes", 59.99))
        );

        String conversationId = "pr-threshold-high";
        slotRepository.saveSlot(conversationId, "matched_item_description", "Running Shoes");
        slotRepository.saveSlot(conversationId, "order_details_json", objectMapper.writeValueAsString(orderDetails));

        QueuedLlmClient llmClient = new QueuedLlmClient();

        ProcessReturnFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "process_return", "check_threshold");

        flow.handlerFor("check_threshold").handle(conversationId, "t1", "");

        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
    }
}