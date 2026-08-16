package in.nishanthraj.orchestrator.domain;

public class GraphExecutor  {

    private final ConversationRepository conversationRepository;

    public GraphExecutor(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    public String step(String conversationId, String input) {
        if (!conversationRepository.exists(conversationId)) {
            conversationRepository.create(conversationId, "chat", "test", "start");
        }
        conversationRepository.updateCurrentNode(conversationId, "confirm");
        return "Got it, thanks!";
    }


}
