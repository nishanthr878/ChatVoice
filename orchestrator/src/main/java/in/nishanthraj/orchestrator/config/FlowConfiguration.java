package in.nishanthraj.orchestrator.config;

import in.nishanthraj.orchestrator.domain.flow.CheckOrderStatusFlow;
import in.nishanthraj.orchestrator.domain.flow.ProcessReturnFlow;
import in.nishanthraj.orchestrator.domain.orchestration.Flow;
import in.nishanthraj.orchestrator.domain.port.*;
import in.nishanthraj.orchestrator.domain.shared.OrderLookupHelper;
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
        OrderLookupHelper orderLookupHelper = new OrderLookupHelper(orderServiceClient, toolInvocationRepository, objectMapper);

        CheckOrderStatusFlow checkOrderStatusFlow = new CheckOrderStatusFlow(conversationRepository,
                                                                                slotRepository,
                                                                                toolInvocationRepository,
                                                                                llmClient,
                                                                                orderServiceClient,
                                                                                orderLookupHelper,
                                                                                objectMapper);

        ProcessReturnFlow processReturnFlow = new ProcessReturnFlow(conversationRepository,
                slotRepository, toolInvocationRepository, llmClient,orderServiceClient, objectMapper, orderLookupHelper);

        return Map.of(checkOrderStatusFlow.flowType(), checkOrderStatusFlow,
                processReturnFlow.flowType(), processReturnFlow);
    }
}
