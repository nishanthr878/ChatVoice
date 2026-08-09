## To test the ordering guarantee of Kafka topic partitioning by `conversation_id`, we can create a test that simulates multiple conversations being processed concurrently. The test will produce messages to the Kafka topic with different `conversation_id`s and verify that messages for the same conversation are consumed in the correct order.

### Test Setup

```bash
docker exec -it agent-platform-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic conversation-events \
  --property print.partition=true \
  --property print.key=true \
  --property print.offset=true \
  --from-beginning
```

```bash

```

### Test by typing below one by one

```text
conversation_id_1:message_1
conv-A:message 1 for A
conv-B:message 1 for B
conv-A:message 2 for A
conv-B:message 2 for B
conv-A:message 3 for A
```

----

#### Output from terminal

```text
    con-A:message 1 for A
    con-B:message 1 for B
    con-A:message 2 for A
    con-B:message 2 for B
    con-A:message 3 for A
    con-B:message 3 for B
    con-A:test Nishanth for A

Partition:1     Offset:0        con-A   message 1 for A
Partition:2     Offset:0        con-B   message 1 for B
Partition:1     Offset:1        con-A   message 2 for A
Partition:2     Offset:1        con-B   message 2 for B
Partition:1     Offset:2        con-A   message 3 for A
Partition:2     Offset:2        con-B   message 3 for B
Partition:1     Offset:3        con-A   test Nishanth for A
```