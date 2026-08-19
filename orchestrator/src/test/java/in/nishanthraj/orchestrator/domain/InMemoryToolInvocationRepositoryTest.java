package in.nishanthraj.orchestrator.domain;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryToolInvocationRepositoryTest {

    @Test
    void neverCalledReturnsEmpty() {
        ToolInvocationRepository repo = new InMemoryConversationRepository.InMemoryToolInvocationRepository();

        Optional<String> result = repo.getResultIfCompleted("conv-1", "turn-1", "lookup_order");

        assertTrue(result.isEmpty());
    }

    @Test
    void pendingCallIsNotReportedAsCompleted() {
        ToolInvocationRepository repo = new InMemoryConversationRepository.InMemoryToolInvocationRepository();

        repo.recordCallStarting("conv-1", "turn-1", "lookup_order", "{\"order_id\":\"123\"}");
        Optional<String> result = repo.getResultIfCompleted("conv-1", "turn-1", "lookup_order");

        assertTrue(result.isEmpty());
    }

    @Test
    void finishedCallReturnsResult() {
        ToolInvocationRepository repo = new InMemoryConversationRepository.InMemoryToolInvocationRepository();

        repo.recordCallStarting("conv-1", "turn-1", "lookup_order", "{\"order_id\":\"123\"}");
        repo.recordCallFinished("conv-1", "turn-1", "lookup_order", "{\"status\":\"delivered\"}", "executed");
        Optional<String> result = repo.getResultIfCompleted("conv-1", "turn-1", "lookup_order");

        assertTrue(result.isPresent());
        assertEquals("{\"status\":\"delivered\"}", result.get());
    }
}