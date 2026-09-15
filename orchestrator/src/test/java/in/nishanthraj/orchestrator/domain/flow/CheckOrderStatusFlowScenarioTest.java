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
                                           LlmClient llmClient,
                                           ConversationStateRepository conversationStateRepository) {
        OrderLookupHelper orderLookupHelper = new OrderLookupHelper(orderServiceClient, toolInvocationRepository, objectMapper);
        return new CheckOrderStatusFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, orderServiceClient, orderLookupHelper, objectMapper, conversationStateRepository
        );
    }

    // --- handleCollectDetails ---

    @Test
    void collectDetails_orderIdPresent_transitionsToLookupOrder() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        QueuedLlmClient llmClient = new QueuedLlmClient("1001", "Let me look that up.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        String conversationId = "cd-order-present";
        conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");
        conversationStateRepository.getOrCreate(conversationId);

        String response = flow.handlerFor("collect_order_id").handle(conversationId, "t1", "check order 1001");

        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));
        ConversationState state = conversationStateRepository.getOrCreate(conversationId);
        assertEquals(Optional.of(new EntityReference("ORDER", "1001")), state.activeFocus());
        assertFalse(response.isBlank());
    }

    @Test
    void collectDetails_noOrderId_staysAndAsksAgain() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        QueuedLlmClient llmClient = new QueuedLlmClient("NONE", "Could you share your order number?");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        String conversationId = "cd-no-order";
        conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");
        conversationStateRepository.getOrCreate(conversationId);

        String response = flow.handlerFor("collect_order_id").handle(conversationId, "t1", "hi there");

        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));
        assertTrue(conversationStateRepository.getOrCreate(conversationId).activeFocus().isEmpty());
        assertFalse(response.isBlank());
    }

    @Test
    void collectDetails_orderIdAlreadyKnown_doesNotAskAgainOnSubsequentTurn() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        String conversationId = "cd-already-known";
        EntityReference existingOrder = new EntityReference("ORDER", "1001");
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.of(existingOrder), List.of(existingOrder)), seeded.version());

        QueuedLlmClient llmClient = new QueuedLlmClient("NONE", "Let me look that up.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");

        String response = flow.handlerFor("collect_order_id").handle(conversationId, "t1", "some unrelated follow-up");

        assertEquals(Optional.of(existingOrder), conversationStateRepository.getOrCreate(conversationId).activeFocus());
        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));
        assertFalse(response.isBlank());
    }

    // --- handleLookupOrder ---

    @Test
    void lookupOrder_success_transitionsToRespondWithDetails() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        orderServiceClient.seed("1001", new OrderServiceClient.OrderDetails(
                "1001", "created", List.of(new OrderServiceClient.OrderLine("item-1", "Running Shoes", 59.99))
        ));

        String conversationId = "lo-success";
        EntityReference order = new EntityReference("ORDER", "1001");
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.of(order), List.of(order)), seeded.version());

        QueuedLlmClient llmClient = new QueuedLlmClient("Found your order.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

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
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        String conversationId = "lo-not-found";
        EntityReference order = new EntityReference("ORDER", "9999"); // deliberately NOT seeded in orderServiceClient
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.of(order), List.of(order)), seeded.version());

        QueuedLlmClient llmClient = new QueuedLlmClient(); // no LLM call expected on this branch

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "check_order_status", "lookup_order");

        String response = flow.handlerFor("lookup_order").handle(conversationId, "t1", "");

        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.toLowerCase().contains("couldn't find"));
    }

    @Test
    void lookupOrder_noActiveFocus_escalatesDefensively() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        String conversationId = "lo-missing-focus";
        conversationStateRepository.getOrCreate(conversationId); // no activeFocus set — simulates corrupted state

        QueuedLlmClient llmClient = new QueuedLlmClient();

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "check_order_status", "lookup_order");

        String response = flow.handlerFor("lookup_order").handle(conversationId, "t1", "");

        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertFalse(response.isBlank());
    }

    // --- handleRespondWithDetails ---

    @Test
    void respondWithDetails_sameOrderOpenEndedQuestion_listsAllItems() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        OrderServiceClient.OrderDetails orderDetails = new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(
                        new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99),
                        new OrderServiceClient.OrderLine("item-2", "Running Shoes", 59.99)
                )
        );

        String conversationId = "rd-same-order";
        EntityReference order = new EntityReference("ORDER", "1001");
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.of(order), List.of(order)), seeded.version());
        slotRepository.saveSlot(conversationId, "order_details_json", objectMapper.writeValueAsString(orderDetails));

        // 1: resolution check -> CURRENT (same order)  2: main reasoning
        QueuedLlmClient llmClient = new QueuedLlmClient(
                "CURRENT",
                "Order 1001 contains a Blue T-Shirt ($19.99) and Running Shoes ($59.99)."
        );

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

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
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        OrderServiceClient.OrderDetails orderDetails = new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(
                        new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99),
                        new OrderServiceClient.OrderLine("item-2", "Running Shoes", 59.99)
                )
        );

        String conversationId = "rd-specific-item";
        EntityReference order = new EntityReference("ORDER", "1001");
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.of(order), List.of(order)), seeded.version());
        slotRepository.saveSlot(conversationId, "order_details_json", objectMapper.writeValueAsString(orderDetails));

        QueuedLlmClient llmClient = new QueuedLlmClient("CURRENT", "The Running Shoes are $59.99.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

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
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        String conversationId = "rd-missing-details";
        EntityReference order = new EntityReference("ORDER", "1001");
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.of(order), List.of(order)), seeded.version());
        // order_details_json deliberately never set

        QueuedLlmClient llmClient = new QueuedLlmClient("CURRENT");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "check_order_status", "respond_with_details");

        String response = flow.handlerFor("respond_with_details").handle(conversationId, "t1", "what items are there?");

        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertFalse(response.isBlank());
    }

    // --- the real payoff: resolving a switch to a different order, including "the other one" ---

    @Test
    void respondWithDetails_userSwitchesToAnotherKnownOrder_reRoutesWithoutFabricating() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        InMemoryConversationStateRepository conversationStateRepository = new InMemoryConversationStateRepository();
        ObjectMapper objectMapper = new ObjectMapper();

        orderServiceClient.seed("1004", new OrderServiceClient.OrderDetails(
                "1004", "delivered", List.of(new OrderServiceClient.OrderLine("item-6", "Desk Lamp", 34.99))
        ));

        String conversationId = "rd-switch-known";
        EntityReference order1001 = new EntityReference("ORDER", "1001");
        EntityReference order1004 = new EntityReference("ORDER", "1004");

        // both orders already known to the conversation (the "the other one" scenario), 1001 currently focused
        ConversationState seeded = conversationStateRepository.getOrCreate(conversationId);
        ConversationState afterFirst = conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.of(order1001), List.of(order1001)), seeded.version());
        conversationStateRepository.applyUpdate(conversationId,
                new ConversationStateUpdate(Optional.empty(), Optional.empty(), List.of(order1004)), afterFirst.version());

        slotRepository.saveSlot(conversationId, "order_details_json", objectMapper.writeValueAsString(
                new OrderServiceClient.OrderDetails("1001", "created", List.of(new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99)))
        ));

        // 1: resolution -> the OTHER known order, not CURRENT  2: phraseNaturally for the switch
        QueuedLlmClient llmClient = new QueuedLlmClient("1004", "Let me pull that up for you.");

        CheckOrderStatusFlow flow = buildFlow(conversationRepository, slotRepository, toolInvocationRepository, orderServiceClient, objectMapper, llmClient, conversationStateRepository);

        conversationRepository.create(conversationId, "chat", "check_order_status", "respond_with_details");

        String response = flow.handlerFor("respond_with_details").handle(conversationId, "t2", "what about the other one?");

        ConversationState afterSwitch = conversationStateRepository.getOrCreate(conversationId);
        assertEquals(Optional.of(order1004), afterSwitch.activeFocus());
        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));
        assertTrue(slotRepository.getSlot(conversationId, "order_details_json").orElseThrow().isEmpty());
        assertFalse(response.toLowerCase().contains("only"));
    }
}