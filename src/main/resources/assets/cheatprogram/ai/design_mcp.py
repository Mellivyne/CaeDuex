"""Local CaeDuex design exchange MCP server, Python standard library only.

Reads explicit building/particle requests and accepts JSON design data in one
configured directory. It has no network, credential, command or world access.
"""
import argparse
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import uuid

MAX_BYTES = 16 * 1024 * 1024
KINDS = {"building", "particles"}


def request_id(value):
    if not isinstance(value, str):
        raise ValueError("request_id must be a UUID")
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError):
        raise ValueError("request_id must be a UUID") from None
    if str(parsed) != value or parsed.version != 4:
        raise ValueError("request_id must be a canonical version-4 UUID")
    return value


def read_object(root, filename):
    path = root / filename
    if path.is_symlink() or path.resolve().parent != root:
        raise ValueError("File outside the design directory")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_BYTES:
            raise ValueError("Design file is invalid or too large")
        data = source.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("Design file is too large")
    value = json.loads(data.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Design file must contain a JSON object")
    return value


def read_request(root, identity):
    identity = request_id(identity)
    value = read_object(root, "request-" + identity + ".json")
    if value.get("id") != identity or value.get("kind") not in KINDS or value.get("version") != 1:
        raise ValueError("Invalid design request")
    return value


def atomic_object(root, filename, value):
    payload = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    if len(payload) > MAX_BYTES:
        raise ValueError("Design is too large")
    descriptor, temporary = tempfile.mkstemp(prefix=".design-", suffix=".tmp", dir=root)
    try:
        with os.fdopen(descriptor, "wb") as target:
            target.write(payload)
        os.replace(temporary, root / filename)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def tools():
    identity = {"type": "string", "description": "Exact UUID from an explicitly exported in-game request"}
    return [
        {"name": "list_design_requests", "description": "List explicitly exported pending local building/particle design requests.",
         "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
         "annotations": {"readOnlyHint": True, "openWorldHint": False}},
        {"name": "get_design_request", "description": "Read one exported request, including instructions, user requirement and previous design. Images are not exported.",
         "inputSchema": {"type": "object", "properties": {"request_id": identity}, "required": ["request_id"], "additionalProperties": False},
         "annotations": {"readOnlyHint": True, "openWorldHint": False}},
        {"name": "submit_design", "description": "Return a complete design JSON object to the game's preview. The game validates it; this does not place blocks or add particles to the world.",
         "inputSchema": {"type": "object", "properties": {"request_id": identity, "design": {"type": "object"}}, "required": ["request_id", "design"], "additionalProperties": False},
         "annotations": {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}},
    ]


def call(root, name, arguments):
    if not isinstance(arguments, dict):
        raise ValueError("arguments must be an object")
    if name == "list_design_requests":
        if arguments:
            raise ValueError("Unexpected arguments")
        pending = []
        for path in sorted(root.glob("request-*.json")):
            try:
                identity = request_id(path.name[8:-5])
                request = read_request(root, identity)
                pending.append({key: request.get(key) for key in ("id", "kind", "createdAt", "imageCount")})
            except (OSError, ValueError, UnicodeError):
                continue
        return {"requests": pending}
    if name not in {"get_design_request", "submit_design"}:
        raise ValueError("Unknown design tool")
    allowed = {"request_id", "design"} if name == "submit_design" else {"request_id"}
    if set(arguments) != allowed:
        raise ValueError("Invalid tool arguments")
    identity = request_id(arguments["request_id"])
    request = read_request(root, identity)
    if name == "get_design_request":
        return request
    design = arguments["design"]
    if not isinstance(design, dict):
        raise ValueError("design must be an object")
    if request["kind"] == "building":
        if not all(key in design for key in ("size", "palette", "fills")):
            raise ValueError("Building must contain size, palette and fills")
    elif not isinstance(design.get("rounds"), list):
        raise ValueError("Particle design must contain rounds")
    # The existing game-side validators remain authoritative for registered blocks/particles.
    atomic_object(root, "result-" + identity + ".json", {"requestId": identity, "kind": request["kind"], "design": design})
    # A closed/replaced request must never receive an accepted stale result.
    try:
        read_request(root, identity)
    except (OSError, ValueError):
        (root / ("result-" + identity + ".json")).unlink(missing_ok=True)
        raise ValueError("The game closed this design request") from None
    return {"submitted": True, "request_id": identity, "message": "Design returned for game-side validation and preview."}


def handle(root, request):
    if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid JSON-RPC request"}}
    if "id" not in request:
        return None
    identity = request["id"]
    method = request.get("method")
    params = request.get("params", {})
    if method == "initialize":
        version = params.get("protocolVersion") if isinstance(params, dict) else None
        if version not in {"2024-11-05", "2025-03-26", "2025-06-18"}:
            version = "2025-06-18"
        result = {"protocolVersion": version, "capabilities": {"tools": {"listChanged": False}},
                  "serverInfo": {"name": "cheat-program-design", "version": "1.0"},
                  "instructions": "Only explicitly exported local design requests are accessible. Return JSON data; the game validates before preview. No account or API key is required."}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": tools()}
    elif method == "tools/call":
        try:
            if not isinstance(params, dict):
                raise ValueError("Invalid params")
            value = call(root, params.get("name"), params.get("arguments", {}))
            result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False, allow_nan=False)}], "isError": False}
        except (OSError, ValueError, TypeError, UnicodeError):
            result = {"content": [{"type": "text", "text": "Design request is invalid, closed, missing, or too large. Check request_id and the JSON schema."}], "isError": True}
    else:
        return {"jsonrpc": "2.0", "id": identity, "error": {"code": -32601, "message": "Method not found"}}
    return {"jsonrpc": "2.0", "id": identity, "result": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    root = parser.parse_args().root.resolve(strict=True)
    if not root.is_dir():
        raise SystemExit("Design exchange directory does not exist")
    while True:
        line = sys.stdin.buffer.readline(MAX_BYTES + 1)
        if not line:
            break
        if len(line) > MAX_BYTES:
            while line and not line.endswith(b"\n"):
                line = sys.stdin.buffer.readline(MAX_BYTES + 1)
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Message is too large"}}
        else:
            try:
                response = handle(root, json.loads(line.decode("utf-8")))
            except (ValueError, TypeError, UnicodeError):
                response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Invalid JSON"}}
        if response is not None:
            payload = json.dumps(response, ensure_ascii=False, allow_nan=False).encode("utf-8")
            sys.stdout.buffer.write(payload + b"\n")
            sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
