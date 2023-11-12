```bash
docker buildx create --name mybuilder --bootstrap --use
docker buildx build --platform linux/amd64,linux/arm64 -t kaminskia/wiki-event-stream:latest --push -f Dockerfile.dev .
```
