package in.nishanthraj.orchestrator.adapter.voice;

import org.springframework.web.socket.WebSocketSession;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class VoiceSession {
    private final WebSocketSession wsSession;
    private final String callSid;
    private final String streamSid;
    private final String conversationId;
    private final ExecutorService executor;
    private final DeepgramSttClient sttClient;
    private volatile DeepgramTtsClient ttsClient;
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final AtomicReference<VoiceSessionManager.State> state =
            new AtomicReference<>(VoiceSessionManager.State.LISTENING);

    public VoiceSession(WebSocketSession wsSession, String callSid, String streamSid,
                        String conversationId, ExecutorService executor, DeepgramSttClient sttClient) {
        this.wsSession = wsSession;
        this.callSid = callSid;
        this.streamSid = streamSid;
        this.conversationId = conversationId;
        this.executor = executor;
        this.sttClient = sttClient;
    }

    public WebSocketSession wsSession() { return wsSession; }
    public String callSid() { return callSid; }
    public String streamSid() { return streamSid; }
    public String conversationId() { return conversationId; }
    public ExecutorService executor() { return executor; }
    public DeepgramSttClient sttClient() { return sttClient; }
    public DeepgramTtsClient ttsClient() { return ttsClient; }
    public void setTtsClient(DeepgramTtsClient ttsClient) { this.ttsClient = ttsClient; }
    public void incrementFrameCount() { frameCount.incrementAndGet(); }
    public int frameCount() { return frameCount.get(); }
    public VoiceSessionManager.State state() { return state.get(); }
    public void setState(VoiceSessionManager.State s) { state.set(s); }
}