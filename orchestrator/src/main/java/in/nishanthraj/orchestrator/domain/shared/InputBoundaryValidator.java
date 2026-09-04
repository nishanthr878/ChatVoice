package in.nishanthraj.orchestrator.domain.shared;

import in.nishanthraj.orchestrator.domain.orchestration.InputValidationResult;
import in.nishanthraj.orchestrator.domain.port.LlmClient;

public class InputBoundaryValidator {

    private final LlmClient llmClient;

    public InputBoundaryValidator(LlmClient llmClient) {
        this.llmClient = llmClient;
    }


    public InputValidationResult validate(String currentTaskDescription, String input) {
        String prompt = "The assistant is currently helping the user with: " + currentTaskDescription + ".\n"
                + "The user's latest message: \"" + input + "\"\n\n"
                + "Does this message provide information relevant to that task, or does it ask about something unrelated "
                + "(like checking order status, when the current task is a return, or vice versa)?\n"
                + "If it's still relevant to the current task, respond with exactly: CONTINUE\n"
                + "If the user is asking about something different, respond with exactly: SWITCH:<intent>, where <intent> is "
                + "one of CHECK_ORDER_STATUS, PROCESS_RETURN, or OTHER.";

        String response = llmClient.complete(prompt);

        if (response.startsWith("SWITCH:")) {
            return new InputValidationResult(InputValidationResult.Decision.SWITCH, response.substring("SWITCH:".length()).trim());
        }
        return new InputValidationResult(InputValidationResult.Decision.CONTINUE, null);
    }
}
