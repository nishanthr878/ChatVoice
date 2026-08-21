package in.nishanthraj.orchestrator.domain.port;

public interface LlmClient {
    String complete(String prompt);
}
