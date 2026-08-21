package in.nishanthraj.orchestrator.domain.port;

import java.util.Optional;

public interface SlotRepository {
    void saveSlot(String conversationId, String slotName, String slotValue);
    Optional<String> getSlot(String conversationId, String slotName);
}
