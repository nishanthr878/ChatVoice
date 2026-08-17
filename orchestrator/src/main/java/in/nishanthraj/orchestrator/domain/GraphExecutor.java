package in.nishanthraj.orchestrator.domain;

import org.springframework.stereotype.Component;

@Component
public class GraphExecutor  {

    private final ConversationRepository conversationRepository;
    private final TurnRepository turnRepository;

    public GraphExecutor(ConversationRepository conversationRepository, TurnRepository turnRepository) {
        this.conversationRepository = conversationRepository;
        this.turnRepository = turnRepository;
    }

    public String step(String conversationId, String input) {
        if (!conversationRepository.exists(conversationId)) {
            conversationRepository.create(conversationId, "chat", "test", "start");
        }
        turnRepository.inserTurn(conversationId, "user", input);
        conversationRepository.updateCurrentNode(conversationId, "confirm");
        return "Got it, thanks!";
    }


}
