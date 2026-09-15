package in.nishanthraj.orchestrator.domain.port;

public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String message) {
        super(message);
    }
}