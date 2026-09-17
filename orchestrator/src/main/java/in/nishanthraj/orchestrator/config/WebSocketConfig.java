package in.nishanthraj.orchestrator.config;

import in.nishanthraj.orchestrator.adapter.voice.VoiceStreamHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceStreamHandler voiceStreamHandler;

    public WebSocketConfig(VoiceStreamHandler voiceStreamHandler) {
        this.voiceStreamHandler = voiceStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceStreamHandler, "/voice/stream");
    }
}