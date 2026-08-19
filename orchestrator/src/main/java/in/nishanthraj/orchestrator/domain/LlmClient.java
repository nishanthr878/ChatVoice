package in.nishanthraj.orchestrator.domain;

public interface LlmClient {
    String complete(String prompt);
}
