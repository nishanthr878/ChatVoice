package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class ProcessReturnFlowTest {

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
    void lowValueItemAutoProcesses() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();
        OrderLookupHelper orderLookupHelper = new OrderLookupHelper(orderServiceClient, toolInvocationRepository, objectMapper);

        orderServiceClient.seed("2002", new OrderServiceClient.OrderDetails(
                "2002", "created",
                List.of(new OrderServiceClient.OrderLine("item-1", "Phone Case", 8.99))
        ));

        QueuedLlmClient llmClient = new QueuedLlmClient("2002", "Phone Case");

        ProcessReturnFlow flow = new ProcessReturnFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient,orderServiceClient, objectMapper, orderLookupHelper
        );

        String conversationId = "return-test-low";
        conversationRepository.create(conversationId, "chat", "process_return", "collect_order_id");

        flow.handlerFor("collect_order_id").handle(conversationId, "t1", "I want to return order 2002");
        assertEquals("collect_item", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("collect_item").handle(conversationId, "t1", "the phone case");
        assertEquals("collect_return_reason", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("collect_return_reason").handle(conversationId, "t1", "wrong color");
        assertEquals("check_threshold", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("check_threshold").handle(conversationId, "t1", "");
        assertEquals("auto_process", conversationRepository.getCurrentNode(conversationId));

        String finalResponse = flow.handlerFor("auto_process").handle(conversationId, "t1", "");
        assertTrue(finalResponse.contains("Phone Case"));
        assertTrue(finalResponse.contains("8.99"));
        assertTrue(finalResponse.contains("wrong color"));
    }

    @Test
    void highValueItemEscalates() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        InMemorySlotRepository slotRepository = new InMemorySlotRepository();
        InMemoryToolInvocationRepository toolInvocationRepository = new InMemoryToolInvocationRepository();
        InMemoryOrderServiceClient orderServiceClient = new InMemoryOrderServiceClient();
        ObjectMapper objectMapper = new ObjectMapper();
        OrderLookupHelper orderLookupHelper = new OrderLookupHelper(orderServiceClient, toolInvocationRepository, objectMapper);

        orderServiceClient.seed("1001", new OrderServiceClient.OrderDetails(
                "1001", "created",
                List.of(new OrderServiceClient.OrderLine("item-1", "Blue T-Shirt", 19.99))
        ));

        QueuedLlmClient llmClient = new QueuedLlmClient("1001", "Blue T-Shirt");

        ProcessReturnFlow flow = new ProcessReturnFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, orderServiceClient, objectMapper, orderLookupHelper
        );

        String conversationId = "return-test-high";
        conversationRepository.create(conversationId, "chat", "process_return", "collect_order_id");

        flow.handlerFor("collect_order_id").handle(conversationId, "t1", "I want to return order 1001");
        flow.handlerFor("collect_item").handle(conversationId, "t1", "the blue shirt");
        flow.handlerFor("collect_return_reason").handle(conversationId, "t1", "doesn't fit");

        String response = flow.handlerFor("check_threshold").handle(conversationId, "t1", "");
        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.toLowerCase().contains("agent"));
    }
}