FROM python:3.14-slim
WORKDIR /app
RUN pip install --no-cache-dir uv
COPY pyproject.toml uv.lock .
RUN uv sync --frozen
COPY . .
EXPOSE 5001
CMD ["uv", "run", "python", "app.py"]