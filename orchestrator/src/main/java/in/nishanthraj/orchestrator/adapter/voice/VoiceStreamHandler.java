package in.nishanthraj.orchestrator.adapter.voice;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class VoiceStreamHandler extends TextWebSocketHandler {

    private final VoiceSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public VoiceStreamHandler(VoiceSessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode node = objectMapper.readTree(message.getPayload());
        String event = node.get("event").asText();

        switch (event) {
            case "start" -> sessionManager.onStart(
                    session,
                    node.get("start").get("callSid").asText(),
                    node.get("start").get("streamSid").asText());
            case "media" -> sessionManager.onMedia(
                    node.get("streamSid").asText(),
                    node.get("media").get("payload").asText());
            case "stop" -> sessionManager.onStop(node.get("streamSid").asText());
            default -> { /* "connected" event — ignore */ }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionManager.onConnectionClosed(session);
    }
}