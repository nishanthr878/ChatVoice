package in.nishanthraj.orchestrator.adapter.voice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class DeepgramTtsClientFactory {
    private final String apiKey;

    public DeepgramTtsClientFactory(@Value("${deepgram.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    public DeepgramTtsClient create(String callSid, WebSocketSession twilioSession, String streamSid, Runnable onFlushed) {
        return new DeepgramTtsClient(apiKey, callSid, twilioSession, streamSid, onFlushed);
    }
}