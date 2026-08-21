package in.nishanthraj.orchestrator.domain.port;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

public interface OrderServiceClient {

    Optional<OrderDetails> getOrder(String orderId);

    record OrderLine(
            @JsonProperty("item_id") String itemId,
            @JsonProperty("description") String description,
            @JsonProperty("unit_price") double unitPrice
    ) {}

    record OrderDetails(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("status") String status,
            @JsonProperty("order_lines") List<OrderLine> orderLines
    ) {}
}