package in.nishanthraj.orchestrator.domain;

public interface Flow {
    String flowType();
    NodeHandler handlerFor(String nodeName);
}
