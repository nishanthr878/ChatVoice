package in.nishanthraj.orchestrator.domain.orchestration;

public record InputValidationResult(Decision decision, String newIntent) {
    public enum Decision {
        CONTINUE,
        SWITCH
    }
}
