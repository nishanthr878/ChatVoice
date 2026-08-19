package in.nishanthraj.orchestrator.domain;

public class StubLlmClient implements LlmClient {

    private final String canned;

    public StubLlmClient(String canned) {
        this.canned = canned;
    }

    @Override
    public String complete(String prompt) {
        return canned;
    }
}