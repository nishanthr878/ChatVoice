CREATE TABLE conversation (
                              conversation_id UUID PRIMARY KEY,
                              channel VARCHAR(16) NOT NULL,
                              flow_type VARCHAR(64) NOT NULL,
                              current_node VARCHAR(64) NOT NULL,
                              status VARCHAR(16) NOT NULL,
                              created_at TIMESTAMPTZ DEFAULT now(),
                              updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE turn (
                      turn_id UUID PRIMARY KEY,
                      conversation_id UUID REFERENCES conversation(conversation_id),
                      speaker VARCHAR(8) NOT NULL,
                      content TEXT NOT NULL,
                      sequence_number INT NOT NULL,
                      created_at TIMESTAMPTZ DEFAULT now(),
                      UNIQUE(conversation_id, sequence_number)
);

CREATE TABLE slot (
                      conversation_id UUID REFERENCES conversation(conversation_id),
                      slot_name VARCHAR(64) NOT NULL,
                      slot_value JSONB NOT NULL,
                      source_turn_id UUID REFERENCES turn(turn_id),
                      filled_at TIMESTAMPTZ DEFAULT now(),
                      PRIMARY KEY (conversation_id, slot_name)
);

CREATE TABLE tool_invocation (
                                 invocation_id UUID PRIMARY KEY,
                                 conversation_id UUID REFERENCES conversation(conversation_id),
                                 idempotency_key VARCHAR(128) UNIQUE NOT NULL,
                                 tool_name VARCHAR(64) NOT NULL,
                                 arguments JSONB NOT NULL,
                                 result JSONB,
                                 status VARCHAR(16) NOT NULL,
                                 created_at TIMESTAMPTZ DEFAULT now(),
                                 updated_at TIMESTAMPTZ DEFAULT now()
);