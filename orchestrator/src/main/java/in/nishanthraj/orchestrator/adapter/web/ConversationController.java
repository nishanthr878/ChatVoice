package in.nishanthraj.orchestrator.adapter.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ConversationController(KafkaTemplate<String, String> kafkaTemplate, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
}
