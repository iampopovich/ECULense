#!/usr/bin/env python3
"""Tiny OpenAI-compatible mock for device checks of the AI wizard and chat (stdlib only, no installs).

    python3 core/ai/tools/mock_openai_server.py [--port 8089] [--key SECRET]
    adb reverse tcp:8089 tcp:8089      # phone -> laptop; base URL in the app: http://127.0.0.1:8089/v1
    adb reverse --remove tcp:8089      # afterwards

GET  /v1/models            -> model catalog (401 if --key is set and the Bearer key differs)
POST /v1/chat/completions  -> SSE stream (or plain JSON) with a Markdown answer: heading, list, bold, code.
Ollama preset (no URL field — Koog's default http://localhost:11434): run a second mock with --port 11434 and
`adb reverse tcp:11434 tcp:11434`.
GET  /api/tags, POST /api/show -> local model list as Koog's OllamaClient.getModels() expects
POST /api/chat             -> NDJSON stream with the same Markdown answer.
The app allows plain http only to local addresses (loopback, private LAN, *.local) — see :core:ai EndpointUrl.
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Koog's OpenAIModel DTO requires id, object, created, owned_by.
MODELS = [
    {"id": "ecu-mock-mini", "object": "model", "created": 1700000000, "owned_by": "mock", "context_length": 8192},
    {"id": "ecu-mock-large", "object": "model", "created": 1700000000, "owned_by": "mock", "context_length": 131072},
    {"id": "qwen2.5-7b-instruct", "object": "model", "created": 1700000000, "owned_by": "mock", "context_length": 32768},
    {"id": "llama-3.1-8b-instruct", "object": "model", "created": 1700000000, "owned_by": "mock"},
    {"id": "mistral-small-24b", "object": "model", "created": 1700000000, "owned_by": "mock"},
]

OLLAMA_MODELS = ["llama3.2:3b", "qwen2.5:7b"]
OLLAMA_DETAILS = {"format": "gguf", "family": "llama", "parameter_size": "3.2B", "quantization_level": "Q4_K_M"}

ANSWER = """## Возможные причины P0171

Смесь **слишком бедная** (банк 1). Чаще всего:

1. **Подсос воздуха** после ДМРВ — трещины патрубков, вакуумные шланги.
2. Загрязнённый *датчик MAF*.
3. Слабый топливный насос или забитый фильтр.

### Что проверить самому

- Долгосрочную коррекцию `LTFT` на холостых и при 2500 об/мин.
- Шланги вентиляции картера.

```
STFT > +10% на ХХ -> ищите подсос
```

> Сброс ошибки — не ремонт.
"""


def chunks(text, size=12):
    for i in range(0, len(text), size):
        yield text[i:i + size]


# Роли экспертов приложения узнаются по системному промпту («Ты — Диагност.»): так на телефоне видно статусы ролей,
# «Над ответом работали» и «Заметки экспертов». Числа — нарочно синтетические, это мок.
ROLES = ["Диагност", "Моторист", "Специалист по охлаждению", "Электрик", "Аналитик эксплуатации", "Экономист", "Мастер ТО",
         "Знаток модели"]

EDITOR_ANSWER = """**Коротко:** за полгода машина ездит ровно, тревожных изменений нет (ответ мок-сервера).

### Что изменилось за период
- Поездок стало больше: 8 → 12 в месяц (синтетика мока).
- Температура ОЖ стабильна: медиана 90 °C.

### На что обратить внимание
- Напряжение на ходу 13,9 В — в норме.
"""


def note_for(role):
    return (f"- {role}: показатель из инструмента 42 → в норме (заметка мок-сервера)\n"
            f"- Уверенность: низкая — это мок, данных нет\n"
            f"- Чего не хватает в данных: настоящей модели")


def answer_for(request):
    """Ответ по системному промпту: редактор, заметка эксперта, прямой ответ роли или общий ответ."""
    messages = request.get("messages", [])
    system = next((m.get("content") or "" for m in messages if m.get("role") == "system"), "")
    if isinstance(system, list):  # content parts
        system = " ".join(p.get("text", "") for p in system if isinstance(p, dict))
    if "Ты — Редактор." in system:
        return "editor", EDITOR_ANSWER
    role = next((r for r in ROLES if f"Ты — {r}." in system), None)
    if role and "заметку для редактора" in system:
        return f"note:{role}", note_for(role)
    if role:
        return f"direct:{role}", f"## {role}\n\n" + note_for(role).replace("заметка", "ответ")
    return "general", ANSWER


class Handler(BaseHTTPRequestHandler):
    key = None

    def _authorized(self):
        if not Handler.key:
            return True
        return self.headers.get("Authorization", "") == f"Bearer {Handler.key}"

    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") == "/api/tags":
            return self._json(200, {"models": [
                {"name": n, "model": n, "size": 2019393189, "modified_at": "2026-01-01T00:00:00Z", "digest": "mock", "details": OLLAMA_DETAILS}
                for n in OLLAMA_MODELS
            ]})
        if self.path.rstrip("/") != "/v1/models":
            return self._json(404, {"error": {"message": "not found"}})
        if not self._authorized():
            return self._json(401, {"error": {"message": "Invalid API key (mock)"}})
        self._json(200, {"object": "list", "data": MODELS})

    def _ollama_chat(self, request):
        model = request.get("model", OLLAMA_MODELS[0])
        print(f"ollama chat model={model} messages={len(request.get('messages', []))}", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.end_headers()
        for piece in chunks(ANSWER):
            line = {"model": model, "message": {"role": "assistant", "content": piece}, "done": False}
            self.wfile.write((json.dumps(line, ensure_ascii=False) + "\n").encode())
            self.wfile.flush()
            time.sleep(0.05)
        done = {"model": model, "message": {"role": "assistant", "content": ""}, "done": True, "eval_count": 1}
        self.wfile.write((json.dumps(done) + "\n").encode())
        self.wfile.flush()

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        request = json.loads(self.rfile.read(length) or b"{}")
        if self.path.rstrip("/") == "/api/show":
            return self._json(200, {
                "details": OLLAMA_DETAILS,
                "model_info": {"general.architecture": "llama", "general.parameter_count": 3212749888,
                               "llama.context_length": 131072, "llama.embedding_length": 3072},
                "capabilities": ["completion", "tools"],
            })
        if self.path.rstrip("/") == "/api/chat":
            return self._ollama_chat(request)
        if self.path.rstrip("/") != "/v1/chat/completions":
            return self._json(404, {"error": {"message": "not found"}})
        if not self._authorized():
            return self._json(401, {"error": {"message": "Invalid API key (mock)"}})
        model = request.get("model", "ecu-mock-mini")
        kind, answer = answer_for(request)
        print(f"chat model={model} messages={len(request.get('messages', []))} stream={request.get('stream')} as={kind}", flush=True)
        if not request.get("stream"):
            return self._json(200, {
                "id": "mock-1", "object": "chat.completion", "created": int(time.time()), "model": model,
                "system_fingerprint": "fp_mock",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": answer}, "finish_reason": "stop"}],
            })
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        def send(delta, finish=None):
            event = {
                "id": "mock-1", "object": "chat.completion.chunk", "created": int(time.time()), "model": model,
                "system_fingerprint": "fp_mock",
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            }
            self.wfile.write(f"data: {json.dumps(event, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()

        for piece in chunks(answer):
            send({"role": "assistant", "content": piece})
            time.sleep(0.05)
        send({}, "stop")
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, format, *args):  # noqa: A002 (base signature)
        print(f"{self.command} {self.path} -> {args[1] if len(args) > 1 else ''}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8089)
    parser.add_argument("--key", default=None)
    args = parser.parse_args()
    Handler.key = args.key
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"mock OpenAI server on http://127.0.0.1:{args.port}/v1", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
