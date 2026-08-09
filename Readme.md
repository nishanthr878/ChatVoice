# Chat bot platform

```bash
docker compose up -d
docker compose ps 



docker exec -it agent-platform-postgres psql -U agent -d agent_platform -c '\dt'
docker exec -it agent-platform-postgres psql -U agent -d agent_platform
docker exec -it agent-platform-postgres psql -U agent -d agent_platform -c '\dt'


docker exec -it agent-platform-kafka /opt/kafka/bin/kafka-consumer-groups.sh   --bootstrap-server localhost:9092   --describe --group conversation-state-consumer
```