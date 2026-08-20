package in.nishanthraj.orchestrator.domain;

public interface NodeHandler {
    String handle(String conversationId,String turnId,  String input);
}
