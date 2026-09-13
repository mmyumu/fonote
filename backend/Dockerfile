# syntax=docker/dockerfile:1

# The server has no dependencies: the image holds only the standard library, the backend
# modules, their competition catalogue and the demo lineup they read. The build context is
# the repository root, since `server.py` looks for that asset next to its own folder.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app

RUN adduser --disabled-password --gecos "" --uid 1001 fonote

COPY backend/*.py backend/competitions.json ./backend/
COPY android/app/src/main/assets/match.json ./android/app/src/main/assets/match.json

# The database lives in a volume: the container stays disposable, the operation log does not.
RUN mkdir -p /data && chown fonote:fonote /data

USER fonote

EXPOSE 8080

# `/v1/health` is the only route open without a token, and it does not touch the database.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/v1/health', timeout=3)" || exit 1

# `/apk` is where compose mounts the Android version to hand out; empty, nothing is offered.
CMD ["python3", "backend/server.py", "--host", "0.0.0.0", "--port", "8080", "--db", "/data/fonote.sqlite3", "--apk-dir", "/apk"]
