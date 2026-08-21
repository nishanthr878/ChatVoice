package in.nishanthraj.orchestrator.domain.port;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemorySlotRepository implements SlotRepository {

    private final Map<String, String> slots = new HashMap<>();

    private String key(String conversationId, String slotName) {
        return conversationId + ":" + slotName;
    }

    @Override
    public void saveSlot(String conversationId, String slotName, String slotValue) {
        slots.put(key(conversationId, slotName), slotValue);
    }

    @Override
    public Optional<String> getSlot(String conversationId, String slotName) {
        return Optional.ofNullable(slots.get(key(conversationId, slotName)));
    }
}
