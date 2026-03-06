# Nexus TTS Server — Self-Hosted

Deploy the TTS engine on your own Linux server or Kubernetes cluster.

## Status

**Coming Soon** — Docker image is being prepared for public release.

## Planned Features

- Docker container with all models pre-packaged
- GPU support via NVIDIA Container Toolkit
- CPU fallback mode (slower but works without GPU)
- Horizontal scaling behind a load balancer
- REST API compatible with OpenAI `/v1/audio/speech`
- Health checks and Prometheus metrics endpoint
- Kubernetes Helm chart included

## Quick Start (When Available)

```bash
# Pull and run with GPU
docker run -d \
  --gpus all \
  -p 8880:8880 \
  --name nexus-tts \
  ghcr.io/adverant/nexus-tts-server:latest

# CPU-only mode
docker run -d \
  -p 8880:8880 \
  -e DEVICE=cpu \
  --name nexus-tts \
  ghcr.io/adverant/nexus-tts-server:latest

# Verify
curl http://localhost:8880/health

# Generate speech
curl -X POST http://localhost:8880/v1/audio/speech \
  -H "Content-Type: application/json" \
  -d '{"model":"kokoro","input":"Hello world","voice":"af_sky"}' \
  -o output.wav
```

## Requirements

- Linux (Ubuntu 22.04+ recommended)
- Docker 24+
- NVIDIA GPU + NVIDIA Container Toolkit (optional, for GPU acceleration)
- 4GB RAM minimum, 8GB recommended
- ~2GB disk for container + models

## Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nexus-tts
spec:
  replicas: 1
  selector:
    matchLabels:
      app: nexus-tts
  template:
    metadata:
      labels:
        app: nexus-tts
    spec:
      containers:
      - name: nexus-tts
        image: ghcr.io/adverant/nexus-tts-server:latest
        ports:
        - containerPort: 8880
        resources:
          limits:
            nvidia.com/gpu: 1
```
