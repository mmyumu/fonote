# syntax=docker/dockerfile:1

# Le serveur n'a aucune dépendance : l'image ne contient que la bibliothèque standard, les
# deux modules du backend et la composition de démonstration qu'ils lisent. Le contexte de
# build est la racine du dépôt, `server.py` cherchant cet asset à côté de son propre dossier.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app

RUN adduser --disabled-password --gecos "" --uid 1001 fonote

COPY backend/server.py backend/espn.py ./backend/
COPY android/app/src/main/assets/match.json ./android/app/src/main/assets/match.json

# La base vit dans un volume : le conteneur reste jetable, le journal des opérations non.
RUN mkdir -p /data && chown fonote:fonote /data

USER fonote

EXPOSE 8080

# `/v1/health` est la seule route ouverte sans jeton, et elle ne touche pas la base.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/v1/health', timeout=3)" || exit 1

CMD ["python3", "backend/server.py", "--host", "0.0.0.0", "--port", "8080", "--db", "/data/fonote.sqlite3"]
