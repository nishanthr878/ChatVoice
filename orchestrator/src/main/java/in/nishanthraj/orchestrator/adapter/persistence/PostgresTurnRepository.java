package in.nishanthraj.orchestrator.adapter.persistence;

import in.nishanthraj.orchestrator.domain.TurnRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresTurnRepository implements TurnRepository {

    private final JdbcTemplate jdbc;

    public PostgresTurnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertTurn(String conversationId, String turnId, String speaker, String content) {
        Integer nextSeq = jdbc.queryForObject("""
            SELECT COALESCE(MAX(sequence_number), 0) + 1
            FROM turn WHERE conversation_id = ?::uuid
            """, Integer.class, conversationId);

        jdbc.update("""
            INSERT INTO turn (turn_id, conversation_id, speaker, content, sequence_number)
            VALUES (?::uuid, ?::uuid, ?, ?, ?)
            """, turnId, conversationId, speaker, content, nextSeq);
    }
}