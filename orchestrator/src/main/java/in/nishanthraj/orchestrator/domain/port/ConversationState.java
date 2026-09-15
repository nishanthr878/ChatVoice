package in.nishanthraj.orchestrator.domain.port;


import java.util.Optional;

public record ConversationState(
        String conversationId,
        String activeIntent,
        Optional<EntityReference> activeFocus,
        int version
) { }


