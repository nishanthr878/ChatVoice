package in.nishanthraj.orchestrator.adapter.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CompletionStage;

public class DeepgramTtsClient {

    private static final Logger log = LoggerFactory.getLogger(DeepgramTtsClient.class);
    private static final String DEEPGRAM_TTS_URL =
            "wss://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=mulaw&sample_rate=8000";

    private final String apiKey;
    private final String callSid;
    private final WebSocketSession twilioSession; // must be ConcurrentWebSocketSessionDecorator-wrapped
    private final String streamSid;
    private final Runnable onFlushed;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile WebSocket webSocket;

    public DeepgramTtsClient(String apiKey, String callSid, WebSocketSession twilioSession,
                             String streamSid, Runnable onFlushed) {
        this.apiKey = apiKey;
        this.callSid = callSid;
        this.twilioSession = twilioSession;
        this.streamSid = streamSid;
        this.onFlushed = onFlushed;
    }

    // BLOCKING -- caller must run off the WebSocket frame-reading thread,
    // same discipline as DeepgramSttClient.connect().
    public void connect() {
        HttpClient httpClient = HttpClient.newHttpClient();
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Token " + apiKey)
                    .buildAsync(URI.create(DEEPGRAM_TTS_URL), new Listener())
                    .join();
            log.info("Deepgram TTS connected for callSid={}", callSid);
        } catch (Exception e) {
            log.error("Failed to connect Deepgram TTS for callSid={}", callSid, e);
        }
    }

    public void speak(String text) {
        if (webSocket == null) {
            log.warn("speak() called before Deepgram TTS connection ready, callSid={}", callSid);
            return;
        }
        ObjectNode speakMsg = objectMapper.createObjectNode().put("type", "Speak").put("text", text);
        webSocket.sendText(speakMsg.toString(), true);
        webSocket.sendText(objectMapper.createObjectNode().put("type", "Flush").toString(), true);
    }

    // Gate 7 territory -- clears Deepgram's own generation buffer.
    // Does NOT clear Twilio's playback buffer; that's a separate message
    // (event: "clear") sent directly to Twilio, not through this client.
    public void cancel() {
        if (webSocket != null) {
            webSocket.sendText(objectMapper.createObjectNode().put("type", "Clear").toString(), true);
        }
    }

    public void close() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "call ended");
        }
    }

    private class Listener implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] mulawBytes = new byte[data.remaining()];
            data.get(mulawBytes);
            sendToTwilio(mulawBytes);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // Deepgram's "Flushed" message is the real completion signal --
            // marks when all audio for this speak() call has been sent,
            // not just that speak() itself returned.
            if (data.toString().contains("\"type\":\"Flushed\"") && onFlushed != null) {
                onFlushed.run();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("Deepgram TTS websocket error for callSid={}", callSid, error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Deepgram TTS closed for callSid={}: {} {}", callSid, statusCode, reason);
            return null;
        }
    }

    private void sendToTwilio(byte[] mulawBytes) {
        try {
            ObjectNode mediaMsg = objectMapper.createObjectNode();
            mediaMsg.put("event", "media");
            mediaMsg.put("streamSid", streamSid);
            mediaMsg.putObject("media").put("payload", Base64.getEncoder().encodeToString(mulawBytes));
            twilioSession.sendMessage(new TextMessage(mediaMsg.toString()));
        } catch (Exception e) {
            log.error("Failed to send TTS audio to Twilio for callSid={}", callSid, e);
        }
    }
}