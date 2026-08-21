package in.nishanthraj.orchestrator.domain.flow;

import in.nishanthraj.orchestrator.domain.port.InMemoryConversationRepository;
import in.nishanthraj.orchestrator.domain.port.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class IntentClassificationFlowTest {

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
    void routesToCheckOrderStatus() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        QueuedLlmClient llmClient = new QueuedLlmClient("CHECK_ORDER_STATUS");
        IntentClassificationFlow flow = new IntentClassificationFlow(conversationRepository, llmClient);

        String conversationId = "intent-test-order";
        conversationRepository.create(conversationId, "chat", "intent_classification", "classify");

        String response = flow.handlerFor("classify").handle(conversationId, "t1", "what's the status of my order 1001");

        assertEquals("check_order_status", conversationRepository.getFlowType(conversationId));
        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.toLowerCase().contains("order number"));
    }

    @Test
    void routesToProcessReturn() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        QueuedLlmClient llmClient = new QueuedLlmClient("PROCESS_RETURN");
        IntentClassificationFlow flow = new IntentClassificationFlow(conversationRepository, llmClient);

        String conversationId = "intent-test-return";
        conversationRepository.create(conversationId, "chat", "intent_classification", "classify");

        String response = flow.handlerFor("classify").handle(conversationId, "t1", "I want to return an item");

        assertEquals("process_return", conversationRepository.getFlowType(conversationId));
        assertEquals("collect_order_id", conversationRepository.getCurrentNode(conversationId));
        assertTrue(response.toLowerCase().contains("order number"));
    }

    @Test
    void escalatesOnUnrecognizedIntent() {
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        QueuedLlmClient llmClient = new QueuedLlmClient("OTHER");
        IntentClassificationFlow flow = new IntentClassificationFlow(conversationRepository, llmClient);

        String conversationId = "intent-test-other";
        conversationRepository.create(conversationId, "chat", "intent_classification", "classify");

        String response = flow.handlerFor("classify").handle(conversationId, "t1", "what's the weather like");

        assertEquals("intent_classification", conversationRepository.getFlowType(conversationId));
        assertTrue(response.toLowerCase().contains("agent"));
    }
}