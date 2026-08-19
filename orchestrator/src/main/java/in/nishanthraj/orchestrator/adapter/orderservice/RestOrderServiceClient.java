package in.nishanthraj.orchestrator.adapter.orderservice;

import in.nishanthraj.orchestrator.domain.OrderServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class RestOrderServiceClient implements OrderServiceClient {

    private final RestClient restClient;

    public RestOrderServiceClient(RestClient.Builder restClientBuilder, @Value("${order.service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<OrderDetails> getOrder(String orderId) {
        try {
            OrderDetails result = restClient.get()
                    .uri("/orders{orderId}",  orderId)
                    .retrieve()
                    .body(OrderDetails.class);
            return Optional.ofNullable(result);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
