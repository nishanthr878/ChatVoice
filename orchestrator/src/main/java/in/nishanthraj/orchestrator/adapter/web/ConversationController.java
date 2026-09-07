package in.nishanthraj.orchestrator.adapter.web;

import in.nishanthraj.orchestrator.domain.port.ConversationRepository;
import in.nishanthraj.orchestrator.domain.port.TurnRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ConversationRepository conversationRepository;
    private final TurnRepository turnRepository;


    public ConversationController(KafkaTemplate<String, String> kafkaTemplate, JdbcTemplate jdbc, ObjectMapper objectMapper, ConversationRepository conversationRepository, TurnRepository turnRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.conversationRepository = conversationRepository;
        this.turnRepository = turnRepository;
    }

    public record SendMessageRequest(String content) {}
    public record KafkaTurnPayload(String speaker, String content) {}
    public record TurnResponse(String speaker, String content, int sequencyNumber) {}

    @PostMapping("/{conversationId}/messages")
    public void sendMessage(@PathVariable String conversationId, @RequestBody SendMessageRequest request) {
        String payload = objectMapper.writeValueAsString(new KafkaTurnPayload("user", request.content()));
        kafkaTemplate.send("conversation-events", conversationId, payload);
    }

    @GetMapping("/{conversationId}/turns")
    public List<TurnResponse> getTurns(@PathVariable String conversationId) {
        return jdbc.query("""
            SELECT speaker, content, sequence_number FROM turn
            WHERE conversation_id = ?::uuid
            ORDER BY sequence_number
            """,
                (rs, rowNum) -> new TurnResponse(rs.getString("speaker"), rs.getString("content"), rs.getInt("sequence_number")),
                conversationId
        );
    }

    @PostMapping("/{conversationId}/start")
    public List<TurnResponse> startConversation(@PathVariable String conversationId) {
        conversationRepository.create(conversationId, "chat", "intent_classification", "classify");

        String turnId = UUID.randomUUID().toString();
        turnRepository.insertTurn(conversationId, turnId, "agent", "Hi, I'm VA - How can I help you today?");

        return getTurns(conversationId);
    }
}
