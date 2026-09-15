package in.nishanthraj.orchestrator.domain.port;

import java.util.List;
import java.util.Optional;

public record ConversationStateUpdate(
        Optional<String> activeIntent,
        Optional<EntityReference> activeFocus,
        List<EntityReference> newEntities
) {
}
