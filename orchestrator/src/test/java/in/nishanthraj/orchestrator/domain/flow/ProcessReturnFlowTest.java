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
    void lowValueItemAutoProcessesWithAllDetailsInOneMessage() {
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

        QueuedLlmClient llmClient = new QueuedLlmClient("ORDER_ID: 2002\nITEM: Phone Case\nREASON: wrong color");

        ProcessReturnFlow flow = new ProcessReturnFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, orderServiceClient, objectMapper, orderLookupHelper
        );

        String conversationId = "return-slot-fill-combined";
        conversationRepository.create(conversationId, "chat", "process_return", "collect_order_id");

        flow.handlerFor("collect_order_id").handle(conversationId, "t1", "return order 2002, the phone case, wrong color");
        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("lookup_order").handle(conversationId, "t1", "");
        assertEquals("check_threshold", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("check_threshold").handle(conversationId, "t1", "");
        assertEquals("auto_process", conversationRepository.getCurrentNode(conversationId));

        String finalResponse = flow.handlerFor("auto_process").handle(conversationId, "t1", "");
        assertTrue(finalResponse.contains("Phone Case"));
        assertTrue(finalResponse.contains("8.99"));
        assertTrue(finalResponse.contains("wrong color"));
        assertEquals("intent_classification", conversationRepository.getFlowType(conversationId));
    }


    @Test
    void highValueItemEscalatesWithSlotsFilledAcrossSeparateTurns() {
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

        QueuedLlmClient llmClient = new QueuedLlmClient(
                "ORDER_ID: 1001\nITEM: NONE\nREASON: NONE",
                "ORDER_ID: NONE\nITEM: Blue T-Shirt\nREASON: NONE",
                "ORDER_ID: NONE\nITEM: NONE\nREASON: doesn't fit"
        );

        ProcessReturnFlow flow = new ProcessReturnFlow(
                conversationRepository, slotRepository, toolInvocationRepository,
                llmClient, orderServiceClient, objectMapper, orderLookupHelper
        );

        String conversationId = "return-slot-fill-separate";
        conversationRepository.create(conversationId, "chat", "process_return", "collect_order_id");

        String r1 = flow.handlerFor("collect_order_id").handle(conversationId, "t1", "order 1001");
        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));
        assertTrue(r1.toLowerCase().contains("item"));

        String r2 = flow.handlerFor("collect_order_id").handle(conversationId, "t2", "the blue t-shirt");
        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));
        assertTrue(r2.toLowerCase().contains("reason"));

        flow.handlerFor("collect_order_id").handle(conversationId, "t3", "it doesn't fit");
        assertEquals("lookup_order", conversationRepository.getCurrentNode(conversationId));

        flow.handlerFor("lookup_order").handle(conversationId, "t3", "");
        String response = flow.handlerFor("check_threshold").handle(conversationId, "t3", "");
        assertEquals("escalate_to_agent", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.toLowerCase().contains("agent"));
    }

}