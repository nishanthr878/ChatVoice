package in.nishanthraj.orchestrator.adapter;

import org.springframework.ai.chat.model.ChatModel;
import in.nishanthraj.orchestrator.domain.port.LlmClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class GroqLlmClient implements LlmClient {

    private final ChatModel chatModel;

    public GroqLlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String complete(String prompt) {
        return chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
    }
}
