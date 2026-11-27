FROM python:3.12-alpine

WORKDIR /app
RUN pip install --no-cache-dir requests

COPY keycloak_events.py .
CMD ["python", "-u", "keycloak_events.py"]
