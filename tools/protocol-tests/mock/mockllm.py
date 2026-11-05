"""Local mock LLM server for protocol tests. Strictly validates request shapes
for OpenAI chat/completions, Anthropic messages and Gemini generateContent."""
import json, sys, re
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

LOG = []
TOOL_PREF = ["current_time", "web_search"]

def pick_tool(names):
    for n in names:
        if n.startswith("ask_") or n.startswith("a2a_"): return n
    for p in TOOL_PREF:
        if p in names: return p
    for n in names:
        if re.match(r"^m[0-9a-f]{4}_echo$", n): return n
    return None

class Bad(Exception): pass

def need(c, msg):
    if not c: raise Bad(msg)

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send_json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def sse(self, events):
        self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
        for ev, data in events:
            if ev: self.wfile.write(f"event: {ev}\n".encode())
            self.wfile.write(f"data: {data if isinstance(data,str) else json.dumps(data)}\n\n".encode())
        self.wfile.flush()

    def do_GET(self):
        p = self.path
        if p.startswith("/v1/models"):
            need_auth = self.headers.get("Authorization")
            return self.send_json(200, {"object": "list", "data": [{"id": "mock-tool"}, {"id": "mock-b"}]})
        if p.startswith("/anthropic/v1/models"):
            if not self.headers.get("x-api-key"): return self.send_json(401, {"error": "no key"})
            return self.send_json(200, {"data": [{"id": "claude-mock"}]})
        if p.startswith("/gemini/v1beta/models"):
            if not self.headers.get("x-goog-api-key"): return self.send_json(401, {"error": "no key"})
            return self.send_json(200, {"models": [
                {"name": "models/gemini-mock", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/embed-mock", "supportedGenerationMethods": ["embedContent"]}]})
        self.send_json(404, {"error": "nf"})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        LOG.append({"path": self.path, "body": body})
        print("REQ", self.path, "tools=", [ (t.get("function") or t).get("name") for t in (body.get("tools") or [])][:8], file=sys.stderr, flush=True)
        try:
            if self.path.startswith("/v1/chat/completions"): return self.openai(body)
            if self.path.startswith("/anthropic/v1/messages"): return self.anthropic(body)
            m = re.match(r"^/gemini/v1beta/models/([^:]+):(generateContent|streamGenerateContent)", self.path)
            if m: return self.gemini(body, m.group(2) == "streamGenerateContent")
            self.send_json(404, {"error": "nf"})
        except Bad as e:
            print("VALIDATION FAIL", self.path, e, file=sys.stderr, flush=True)
            self.send_json(400, {"error": {"message": "mock validation: " + str(e)}})

    # ---------- OpenAI ----------
    def openai(self, b):
        need(self.headers.get("Authorization", "").startswith("Bearer "), "missing bearer")
        need(isinstance(b.get("model"), str) and b["model"], "model")
        msgs = b.get("messages"); need(isinstance(msgs, list) and msgs, "messages")
        pending = set()
        for m in msgs:
            need(m.get("role") in ("system", "user", "assistant", "tool"), "role " + str(m.get("role")))
            if m["role"] == "assistant":
                for tc in m.get("tool_calls", []) or []:
                    need(tc.get("type") == "function", "tc type")
                    need(isinstance(tc["function"]["arguments"], str), "arguments must be string")
                    json.loads(tc["function"]["arguments"]); pending.add(tc["id"])
            if m["role"] == "tool":
                need(m.get("tool_call_id") in pending, "tool_call_id not matching")
                need(isinstance(m.get("content"), str), "tool content str")
        tools = [t["function"]["name"] for t in b.get("tools", []) or []]
        for t in b.get("tools", []) or []:
            need(re.match(r"^[A-Za-z0-9_-]{1,64}$", t["function"]["name"]), "bad tool name")
            need(t["function"]["parameters"].get("type") == "object", "params object")
        last = msgs[-1]
        if b.get("stream"):
            text = "Hello from mock OpenAI"
            if tools and last["role"] != "tool":
                tn = pick_tool(tools)
            else: tn = None
            evs = []
            if tn:
                evs.append((None, {"choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [{"index": 0, "id": "call_s1", "type": "function", "function": {"name": tn, "arguments": ""}}]}}]}))
                evs.append((None, {"choices": [{"index": 0, "delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{}"}}]}, "finish_reason": "tool_calls"}]}))
            else:
                if last["role"] == "tool": text = "Final via tool: " + last["content"][:60]
                for w in text.split(" "):
                    evs.append((None, {"choices": [{"index": 0, "delta": {"content": w + " "}}]}))
                evs.append((None, {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10}}))
            evs.append((None, "[DONE]"))
            return self.sse(evs)
        # non-stream
        if tools and last["role"] != "tool":
            tn = pick_tool(tools)
            if tn:
                args = {}
                props = next(t for t in b["tools"] if t["function"]["name"] == tn)["function"]["parameters"].get("properties", {})
                for k in props: args[k] = "test " + k
                return self.send_json(200, {"choices": [{"index": 0, "message": {"role": "assistant", "content": None,
                    "tool_calls": [{"id": "call_%d" % len(LOG), "type": "function", "function": {"name": tn, "arguments": json.dumps(args)}}]}, "finish_reason": "tool_calls"}]})
        text = ("Final via tool: " + last["content"][:200]) if last["role"] == "tool" else "Plain answer to: " + str(last.get("content"))[:80]
        return self.send_json(200, {"choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"}]})

    # ---------- Anthropic ----------
    def anthropic(self, b):
        need(self.headers.get("x-api-key"), "x-api-key"); need(self.headers.get("anthropic-version"), "anthropic-version")
        need(isinstance(b.get("max_tokens"), int), "max_tokens")
        msgs = b["messages"]; need(msgs and msgs[0]["role"] == "user", "first must be user")
        prev = None; tool_ids = set()
        for m in msgs:
            need(m["role"] in ("user", "assistant"), "role")
            need(m["role"] != prev, "roles must alternate (got two %s)" % m["role"]); prev = m["role"]
            c = m["content"]
            if isinstance(c, list):
                need(len(c) > 0, "empty content")
                for blk in c:
                    if blk["type"] == "tool_use": tool_ids.add(blk["id"]); need(isinstance(blk["input"], dict), "input dict")
                    if blk["type"] == "tool_result": need(blk["tool_use_id"] in tool_ids, "tool_result id mismatch")
                    if blk["type"] == "text": need(blk["text"] != "", "empty text block")
        tools = [t["name"] for t in b.get("tools", []) or []]
        for t in b.get("tools", []) or []: need(t["input_schema"].get("type") == "object", "input_schema")
        last = msgs[-1]
        last_is_result = isinstance(last["content"], list) and any(x["type"] == "tool_result" for x in last["content"])
        if b.get("stream"):
            text = "Hello from mock Claude"
            evs = [("message_start", {"type": "message_start", "message": {"id": "m1", "role": "assistant", "content": []}}),
                   ("content_block_start", {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}})]
            for w in text.split(" "):
                evs.append(("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": w + " "}}))
            evs += [("content_block_stop", {"type": "content_block_stop", "index": 0}), ("message_stop", {"type": "message_stop"})]
            return self.sse(evs)
        if tools and not last_is_result:
            tn = pick_tool(tools)
            if tn:
                props = next(t for t in b["tools"] if t["name"] == tn)["input_schema"].get("properties", {})
                return self.send_json(200, {"content": [{"type": "thinking", "thinking": "hmm", "signature": "sig"},
                    {"type": "text", "text": "Let me check."},
                    {"type": "tool_use", "id": "toolu_%d" % len(LOG), "name": tn, "input": {k: "test " + k for k in props}}], "stop_reason": "tool_use"})
        if last_is_result:
            r = [x for x in last["content"] if x["type"] == "tool_result"][0]["content"]
            text = "Final via tool: " + str(r)[:200]
        else: text = "Plain Claude answer"
        return self.send_json(200, {"content": [{"type": "text", "text": text}], "stop_reason": "end_turn"})

    # ---------- Gemini ----------
    def gemini(self, b, stream):
        need(self.headers.get("x-goog-api-key"), "x-goog-api-key")
        cs = b["contents"]; need(cs and cs[0]["role"] == "user", "first user")
        prev = None; calls = []
        for c in cs:
            need(c["role"] in ("user", "model"), "role")
            need(c["role"] != prev, "roles alternate"); prev = c["role"]
            for p in c["parts"]:
                if "functionCall" in p: calls.append(p["functionCall"]["name"]); need("thoughtSignature" in p, "thoughtSignature must be echoed back")
                if "functionResponse" in p: need(p["functionResponse"]["name"] in calls, "functionResponse name mismatch")
        decls = []
        for t in b.get("tools", []) or []:
            for d in t.get("functionDeclarations", []):
                decls.append(d["name"])
                def chk(s):
                    if isinstance(s, dict):
                        for k in s:
                            need(k in ("type","description","properties","items","required","enum","format","nullable") or k in (s.get("_p") or []), "gemini schema key " + k) if k not in ("properties",) else None
                            if k == "properties":
                                for v in s[k].values(): chk(v)
                            elif k == "items": chk(s[k])
                if "parameters" in d: chk(d["parameters"])
        last = cs[-1]
        last_is_resp = any("functionResponse" in p for p in last["parts"])
        if stream:
            evs = [(None, {"candidates": [{"content": {"role": "model", "parts": [{"text": w + " "}]}}]}) for w in "Hello from mock Gemini".split(" ")]
            return self.sse(evs)
        if decls and not last_is_resp:
            tn = pick_tool(decls)
            if tn:
                return self.send_json(200, {"candidates": [{"content": {"role": "model", "parts": [
                    {"text": "thinking...", "thought": True},
                    {"functionCall": {"name": tn, "args": {"query": "x"} if tn == "web_search" else {}}, "thoughtSignature": "SIG123"}]}}]})
        text = "Final via tool: " + json.dumps([p for p in last["parts"] if "functionResponse" in p][0]["functionResponse"]["response"])[:200] if last_is_resp else "Plain Gemini answer"
        return self.send_json(200, {"candidates": [{"content": {"role": "model", "parts": [{"text": text}]}}]})

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8787), H).serve_forever()
