package in.nishanthraj.orchestrator.domain;

import in.nishanthraj.orchestrator.adapter.orderservice.RestOrderServiceClient; // remove if not needed
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CheckOrderStatusFlowTest {

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

    @Test
    void fullCheckOrderStatusFlowEndToEnd() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryConversationRepository.InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryConversationRepository.InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        orderServiceClient.seed("1001", new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99))
        ));

        QueuedLlmClient llmClient = new QueuedLlmClient("1001", "Blue T-Shirt");

        CheckOrderStatusFlow flow = new CheckOrderStatusFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, orderServiceClient, objectMapper
        );

        String conversationId = "test-conversation";
        conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");

        String r1 = flow.handlerFor("collect_order_id").handle(conversationId, "turn-1", "I want to check order 1001");
        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));

        String r2 = flow.handlerFor("lookup_order").handle(conversationId, "turn-1", "1001");
        assertEquals("collect_item", conversationRepository.getCurrentNode(conversationId));

        String r3 = flow.handlerFor("collect_item").handle(conversationId, "turn-1", "the blue shirt");
        assertEquals("match_item", conversationRepository.getCurrentNode(conversationId));

        String r4 = flow.handlerFor("match_item").handle(conversationId, "turn-1", "yes");
        assertEquals("respond_with_details", conversationRepository.getCurrentNode(conversationId));

        String r5 = flow.handlerFor("respond_with_details").handle(conversationId, "turn-1", "");
        assertTrue(r5.contains("1001"));
        assertTrue(r5.contains("Blue T-Shirt"));
        assertTrue(r5.contains("19.99"));
    }

    @Test
    void flowWorksAcrossDifferentTurnIds() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryConversationRepository.InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryConversationRepository.InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();

        orderServiceClient.seed("1001", new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99))
        ));

        QueuedLlmClient llmClient = new QueuedLlmClient("1001", "Blue T-Shirt");

        CheckOrderStatusFlow flow = new CheckOrderStatusFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, orderServiceClient, objectMapper
        );

        String conversationId = "test-conversation-2";
        conversationRepository.create(conversationId, "chat", "check_order_status", "collect_order_id");

        flow.handlerFor("collect_order_id").handle(conversationId, UUID.randomUUID().toString(), "I want to check order 1001");
        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("lookup_order").handle(conversationId, UUID.randomUUID().toString(), "1001");
        assertEquals("collect_item", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("collect_item").handle(conversationId, UUID.randomUUID().toString(), "the blue shirt");
        assertEquals("match_item", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("match_item").handle(conversationId, UUID.randomUUID().toString(), "yes");
        assertEquals("respond_with_details", conversationRepository.getCurrentNode(conversationId));

        String finalResponse = flow.handlerFor("respond_with_details").handle(conversationId, UUID.randomUUID().toString(), "");
        assertTrue(finalResponse.contains("1001"));
        assertTrue(finalResponse.contains("Blue T-Shirt"));
        assertTrue(finalResponse.contains("19.99"));
    }
}