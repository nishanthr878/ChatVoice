package in.nishanthraj.orchestrator.adapter.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public class DeepgramSttClient {

    private static final Logger log = LoggerFactory.getLogger(DeepgramSttClient.class);
    private static final String DEEPGRAM_URL =
            "wss://api.deepgram.com/v1/listen?encoding=mulaw&sample_rate=8000&channels=1&punctuate=true";

    private final String apiKey;
    private final String callSid;
    private final Consumer<String> onSpeechFinal;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile WebSocket webSocket;
    private final StringBuilder textBuffer = new StringBuilder();

    public DeepgramSttClient(String apiKey, String callSid, Consumer<String> onSpeechFinal) {
        this.apiKey = apiKey;
        this.callSid = callSid;
        this.onSpeechFinal = onSpeechFinal;
    }

    // BLOCKING (.join()) -- caller must run this off the WebSocket
    // frame-reading thread. VoiceSessionManager does this via the
    // session's own executor. Do not call this inline from onStart.
    public void connect() {
        HttpClient httpClient = HttpClient.newHttpClient();
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Token " + apiKey)
                    .buildAsync(URI.create(DEEPGRAM_URL), new Listener())
                    .join();
            log.info("Deepgram STT connected for callSid={}", callSid);
        } catch (Exception e) {
            log.error("Failed to connect Deepgram STT for callSid={}", callSid, e);
        }
    }

    // payload is the raw base64 string from Twilio's "media" event
    public void sendAudio(String base64MulawPayload) {
        if (webSocket == null) {
            log.warn("sendAudio called before Deepgram connection ready, callSid={}", callSid);
            return;
        }
        byte[] rawMulaw = Base64.getDecoder().decode(base64MulawPayload);
        webSocket.sendBinary(ByteBuffer.wrap(rawMulaw), true);
    }

    public void close() {
        if (webSocket != null) {
            webSocket.sendText("{\"type\":\"CloseStream\"}", true)
                    .thenRun(() -> webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "call ended"));
        }
    }

    private class Listener implements WebSocket.Listener {

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String fullMessage = textBuffer.toString();
                textBuffer.setLength(0);
                handleMessage(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("Deepgram STT websocket error for callSid={}", callSid, error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Deepgram STT closed for callSid={}: {} {}", callSid, statusCode, reason);
            return null;
        }
    }

    private void handleMessage(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode channel = node.get("channel");
            if (channel == null) return; // Metadata/UtteranceEnd messages -- ignore

            boolean speechFinal = node.path("speech_final").asBoolean(false);
            String transcript = channel.path("alternatives").get(0).path("transcript").asString("");

            if (speechFinal && !transcript.isBlank()) {
                log.info("Deepgram speech_final for callSid={}: {}", callSid, transcript);
                onSpeechFinal.accept(transcript);
            }
        } catch (Exception e) {
            log.error("Failed to parse Deepgram message for callSid={}: {}", callSid, json, e);
        }
    }
}