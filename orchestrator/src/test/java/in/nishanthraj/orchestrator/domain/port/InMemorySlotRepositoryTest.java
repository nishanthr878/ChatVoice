package in.nishanthraj.orchestrator.domain.port;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Optional;

public class InMemorySlotRepositoryTest  {

    @Test
    void savedSlotCanBeReadBack() {
        SlotRepository repo = new InMemorySlotRepository();
        repo.saveSlot("some-conversation-id", "order_id", "12345");

        Optional<String> result = repo.getSlot("some-conversation-id", "order_id");

        assertTrue(result.isPresent());
        assertEquals("12345", result.get());
    }

    @Test
    void missingSlotReturnsEmpty() {
        SlotRepository repo = new InMemorySlotRepository();

        Optional<String> result = repo.getSlot("some-conversation-id", "never_filled");

        assertTrue(result.isEmpty());
    }

}
