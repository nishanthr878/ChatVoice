package in.nishanthraj.orchestrator.adapter.voice;

import in.nishanthraj.orchestrator.domain.orchestration.GraphExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class VoiceSessionManager {

    private static final Logger log = LoggerFactory.getLogger(VoiceSessionManager.class);

    public enum State { LISTENING, PROCESSING, SPEAKING }

    private final GraphExecutor graphExecutor;
    private final DeepgramSttClientFactory sttClientFactory;
    private final DeepgramTtsClientFactory ttsClientFactory;
    private final Map<String, VoiceSession> sessionsByStreamSid = new ConcurrentHashMap<>();

    public VoiceSessionManager(GraphExecutor graphExecutor,
                               DeepgramSttClientFactory sttClientFactory,
                               DeepgramTtsClientFactory ttsClientFactory) {
        this.graphExecutor = graphExecutor;
        this.sttClientFactory = sttClientFactory;
        this.ttsClientFactory = ttsClientFactory;
    }

    public void onStart(WebSocketSession rawWsSession, String callSid, String streamSid) {
        String conversationId = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        WebSocketSession wsSession = new ConcurrentWebSocketSessionDecorator(rawWsSession, 5000, 65536);

        DeepgramSttClient sttClient = sttClientFactory.create(callSid,
                transcript -> onSpeechFinal(streamSid, transcript));

        VoiceSession session = new VoiceSession(wsSession, callSid, streamSid, conversationId, executor, sttClient);
        DeepgramTtsClient ttsClient = ttsClientFactory.create(callSid, wsSession, streamSid,
                () -> session.setState(State.LISTENING));
        session.setTtsClient(ttsClient);

        sessionsByStreamSid.put(streamSid, session);
        executor.submit(() -> {
            sttClient.connect();
            ttsClient.connect();
        });

        log.info("Voice call started: callSid={} streamSid={} conversationId={}", callSid, streamSid, conversationId);
    }

    public void onMedia(String streamSid, String base64MulawPayload) {
        VoiceSession session = sessionsByStreamSid.get(streamSid);
        if (session == null) {
            log.warn("Media for unknown streamSid={}", streamSid);
            return;
        }
        session.incrementFrameCount();
        session.sttClient().sendAudio(base64MulawPayload);
    }

    private void onSpeechFinal(String streamSid, String transcribedText) {
        VoiceSession session = sessionsByStreamSid.get(streamSid);
        if (session == null) return;

        session.setState(State.PROCESSING);
        session.executor().submit(() -> {
            try {
                String response = graphExecutor.step(session.conversationId(), transcribedText, "voice");
                session.setState(State.SPEAKING);
                session.ttsClient().speak(response);
            } catch (Exception e) {
                log.error("step() failed for callSid={}", session.callSid(), e);
                session.setState(State.LISTENING);
            }
        });
    }

    public void onStop(String streamSid) {
        VoiceSession session = sessionsByStreamSid.remove(streamSid);
        if (session != null) {
            session.sttClient().close();
            session.ttsClient().close();
            session.executor().shutdown();
            log.info("Voice call stopped: callSid={} conversationId={} totalFrames={}",
                    session.callSid(), session.conversationId(), session.frameCount());
        }
    }

    public void onConnectionClosed(WebSocketSession wsSession) {
        sessionsByStreamSid.values().stream()
                .filter(s -> s.wsSession().equals(wsSession))
                .findFirst()
                .ifPresent(s -> {
                    s.sttClient().close();
                    s.ttsClient().close();
                    s.executor().shutdown();
                    sessionsByStreamSid.remove(s.streamSid());
                });
    }
}