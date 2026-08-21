package in.nishanthraj.orchestrator.domain.orchestration;

public interface NodeHandler {
    String handle(String conversationId,String turnId,  String input);
}
