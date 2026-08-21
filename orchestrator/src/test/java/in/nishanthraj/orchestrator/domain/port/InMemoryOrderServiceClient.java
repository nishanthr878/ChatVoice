package in.nishanthraj.orchestrator.domain.port;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryOrderServiceClient implements OrderServiceClient {

    private final Map<String, OrderDetails> orders = new HashMap<>();

    public void seed(String orderId, OrderDetails details) {
        orders.put(orderId, details);
    }

    @Override
    public Optional<OrderDetails> getOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}