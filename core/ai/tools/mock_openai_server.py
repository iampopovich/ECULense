#!/usr/bin/env python3
"""Tiny OpenAI-compatible mock for device checks of the AI wizard and chat (stdlib only, no installs).

    python3 core/ai/tools/mock_openai_server.py [--port 8089] [--key SECRET]
    adb reverse tcp:8089 tcp:8089      # phone -> laptop; base URL in the app: http://127.0.0.1:8089/v1
    adb reverse --remove tcp:8089      # afterwards

GET  /v1/models            -> model catalog (401 if --key is set and the Bearer key differs)
POST /v1/chat/completions  -> SSE stream (or plain JSON) with a Markdown answer: heading, list, bold, code.
Debug builds allow cleartext to 127.0.0.1 only (app/src/debug network security config).
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODELS = [
    {"id": "ecu-mock-mini", "object": "model", "owned_by": "mock", "context_length": 8192},
    {"id": "ecu-mock-large", "object": "model", "owned_by": "mock", "context_length": 131072},
    {"id": "qwen2.5-7b-instruct", "object": "model", "owned_by": "mock", "context_length": 32768},
    {"id": "llama-3.1-8b-instruct", "object": "model", "owned_by": "mock"},
    {"id": "mistral-small-24b", "object": "model", "owned_by": "mock"},
]

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
        if self.path.rstrip("/") != "/v1/models":
            return self._json(404, {"error": {"message": "not found"}})
        if not self._authorized():
            return self._json(401, {"error": {"message": "Invalid API key (mock)"}})
        self._json(200, {"object": "list", "data": MODELS})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        request = json.loads(self.rfile.read(length) or b"{}")
        if self.path.rstrip("/") != "/v1/chat/completions":
            return self._json(404, {"error": {"message": "not found"}})
        if not self._authorized():
            return self._json(401, {"error": {"message": "Invalid API key (mock)"}})
        model = request.get("model", "ecu-mock-mini")
        print(f"chat model={model} messages={len(request.get('messages', []))} stream={request.get('stream')}", flush=True)
        if not request.get("stream"):
            return self._json(200, {
                "id": "mock-1", "object": "chat.completion", "created": int(time.time()), "model": model,
                "choices": [{"index": 0, "message": {"role": "assistant", "content": ANSWER}, "finish_reason": "stop"}],
            })
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        def send(delta, finish=None):
            event = {
                "id": "mock-1", "object": "chat.completion.chunk", "created": int(time.time()), "model": model,
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            }
            self.wfile.write(f"data: {json.dumps(event, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()

        for piece in chunks(ANSWER):
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
