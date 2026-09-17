package in.nishanthraj.orchestrator.adapter.voice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.function.Consumer;

@Component
public class DeepgramSttClientFactory {
    private final String apiKey;

    public DeepgramSttClientFactory(@Value("${deepgram.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    public DeepgramSttClient create(String callSid, Consumer<String> onSpeechFinal) {
        return new DeepgramSttClient(apiKey, callSid, onSpeechFinal);
    }
}