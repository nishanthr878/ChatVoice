package in.nishanthraj.orchestrator.config;

import in.nishanthraj.orchestrator.domain.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Configuration
public class FlowConfiguration {

    @Bean
    public Map<String, Flow> flowsByType(ConversationRepository conversationRepository,
                                         SlotRepository slotRepository,
                                         ToolInvocationRepository toolInvocationRepository,
                                         LlmClient llmClient,
                                         OrderServiceClient orderServiceClient,
                                         ObjectMapper objectMapper) {
        CheckOrderStatusFlow checkOrderStatusFlow = new CheckOrderStatusFlow(conversationRepository,
                                                                                slotRepository,
                                                                                toolInvocationRepository,
                                                                                llmClient,
                                                                                orderServiceClient,
                                                                                objectMapper);

        return Map.of(checkOrderStatusFlow.flowType(), checkOrderStatusFlow);
    }
}
