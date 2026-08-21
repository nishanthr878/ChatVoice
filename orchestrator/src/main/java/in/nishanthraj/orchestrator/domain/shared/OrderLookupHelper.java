package in.nishanthraj.orchestrator.domain.shared;

import in.nishanthraj.orchestrator.domain.port.OrderServiceClient;
import in.nishanthraj.orchestrator.domain.port.ToolInvocationRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

public class OrderLookupHelper {

    private final OrderServiceClient orderServiceClient;
    private final ToolInvocationRepository toolInvocationRepository;
    private final ObjectMapper objectMapper;

    public OrderLookupHelper (OrderServiceClient orderServiceClient,
                              ToolInvocationRepository toolInvocationRepository,
                              ObjectMapper objectMapper) {
        this.orderServiceClient = orderServiceClient;
        this.toolInvocationRepository = toolInvocationRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<String> lookupOrder(String conversationId, String turnId, String orderId) {
        Optional<String> cachedResult = toolInvocationRepository.getResultIfCompleted(conversationId, turnId, "lookup_order");
        if (cachedResult.isPresent()) {
            return cachedResult;
        }

        toolInvocationRepository.recordCallStarting(conversationId, turnId, "lookup_order", orderId);

        Optional<OrderServiceClient.OrderDetails> orderDetails = orderServiceClient.getOrder(orderId);
        if (orderDetails.isEmpty()) {
            toolInvocationRepository.recordCallFinished(conversationId, turnId, "lookup_order", "", "failed");
            return Optional.empty();
        }

        String resultJson = objectMapper.writeValueAsString(orderDetails.get());
        toolInvocationRepository.recordCallFinished(conversationId, turnId, "lookup_order", resultJson, "executed");
        return Optional.of(resultJson);
    }

    public Optional<OrderServiceClient.OrderLine> findMatchingLine(OrderServiceClient.OrderDetails orderDetails,
                                                                   String matchedDescription) {
        return orderDetails.orderLines().stream()
                .filter(line -> line.description().equals(matchedDescription))
                .findFirst();
    }

}
