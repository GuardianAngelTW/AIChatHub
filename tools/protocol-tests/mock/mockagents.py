"""Mock MCP (Streamable HTTP, JSON and SSE responses) and A2A (JSON-RPC message/send) servers."""
import json, sys, uuid
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

class H(BaseHTTPRequestHandler):
    sessions = set()
    def log_message(self, *a): pass
    def reply(self, code, obj, headers=None, sse=False):
        b = json.dumps(obj)
        self.send_response(code)
        for k, v in (headers or {}).items(): self.send_header(k, v)
        if sse:
            data = ("event: message\ndata: " + b + "\n\n").encode()
            self.send_header("Content-Type", "text/event-stream")
        else:
            data = b.encode(); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path == "/.well-known/agent-card.json":
            port = self.server.server_address[1]
            return self.reply(200, {"name": "Echo Agent", "description": "Echoes tasks", "url": f"http://127.0.0.1:{port}/a2a",
                                    "version": "1.0", "skills": [{"id": "echo", "name": "echo"}], "capabilities": {}})
        self.reply(404, {})
    def do_POST(self):
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.path in ("/mcp", "/mcp-sse"):
            sse = self.path == "/mcp-sse"
            if self.headers.get("Authorization") != "Bearer mcptok": return self.reply(401, {"error": "unauthorized"})
            acc = self.headers.get("Accept", "")
            if "application/json" not in acc or "text/event-stream" not in acc: return self.reply(406, {"error": "Accept"})
            m = req.get("method")
            if m == "initialize":
                sid = uuid.uuid4().hex; H.sessions.add(sid)
                return self.reply(200, {"jsonrpc": "2.0", "id": req["id"], "result": {"protocolVersion": "2025-06-18",
                    "capabilities": {"tools": {}}, "serverInfo": {"name": "mock-mcp", "version": "1"}}}, {"Mcp-Session-Id": sid}, sse)
            sid = self.headers.get("Mcp-Session-Id")
            if sid not in H.sessions: return self.reply(400 if not sid else 404, {"error": "session"})
            if "id" not in req: self.send_response(202); self.end_headers(); return
            if m == "tools/list":
                return self.reply(200, {"jsonrpc": "2.0", "id": req["id"], "result": {"tools": [{"name": "echo", "description": "Echo text",
                    "inputSchema": {"$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "additionalProperties": False,
                                    "properties": {"message": {"type": "string"}}, "required": ["message"]}}]}}, None, sse)
            if m == "tools/call":
                a = req["params"].get("arguments", {})
                return self.reply(200, {"jsonrpc": "2.0", "id": req["id"], "result": {"content": [{"type": "text", "text": "ECHO:" + str(a.get("message"))}]}}, None, sse)
            return self.reply(200, {"jsonrpc": "2.0", "id": req["id"], "error": {"code": -32601, "message": "nf"}}, None, sse)
        if self.path == "/a2a":
            if req.get("method") != "message/send":
                return self.reply(200, {"jsonrpc": "2.0", "id": req.get("id"), "error": {"code": -32601, "message": "Method not found"}})
            msg = req["params"]["message"]
            text = "".join(p.get("text", "") for p in msg["parts"] if p.get("kind") == "text")
            assert msg["role"] == "user" and msg.get("messageId")
            return self.reply(200, {"jsonrpc": "2.0", "id": req["id"], "result": {"kind": "task", "id": "t1", "contextId": "c1",
                "status": {"state": "completed", "message": {"role": "agent", "kind": "message", "messageId": "m2", "parts": [{"kind": "text", "text": "A2A echo: " + text}]}},
                "artifacts": [{"artifactId": "a1", "parts": [{"kind": "text", "text": "artifact: " + text}]}],
                "history": [msg]}})
        self.reply(404, {})

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
