package in.nishanthraj.orchestrator.adapter.voice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TwilioWebhookController {

    private final String publicHost;

    public TwilioWebhookController(@Value("${voice.public-host}") String publicHost) {
        this.publicHost = publicHost;
    }

    @PostMapping(value = "/voice/incoming", produces = MediaType.APPLICATION_XML_VALUE)
    public String incomingCall() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
                <Connect>
                    <Stream url="wss://%s/voice/stream" />
                </Connect>
            </Response>
            """.formatted(publicHost);
    }
}