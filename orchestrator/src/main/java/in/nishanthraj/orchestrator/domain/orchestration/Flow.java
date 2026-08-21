package in.nishanthraj.orchestrator.domain.orchestration;

public interface Flow {
    String flowType();
    NodeHandler handlerFor(String nodeName);
}
