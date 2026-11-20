"""Connect a local MCP client to one explicitly exported CaeDuex gameplay session."""
import argparse
from contextlib import contextmanager
import json
import math
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time
import uuid

from design_mcp import request_id


MAX_BYTES = 256 * 1024
READY_TIMEOUT = 10
REPLY_TIMEOUT = 90
KINDS = {"control", "player"}
PROTOCOLS = {"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"}
ACTION_NAME = re.compile(r"[a-z][a-z0-9_]{0,63}\Z")


def mailbox_path(root, filename):
    path = root / filename
    if path.is_symlink() or path.resolve().parent != root:
        raise ValueError("Mailbox file is outside the gameplay directory")
    return path


def reject_constant(_value):
    raise ValueError("Non-finite numbers are invalid JSON")


def finite_float(value):
    number = float(value)
    if not math.isfinite(number):
        reject_constant(value)
    return number


def parse_json(data):
    return json.loads(data.decode("utf-8"), parse_constant=reject_constant, parse_float=finite_float)


def read_object(root, filename):
    path = mailbox_path(root, filename)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0) | getattr(os, "O_NONBLOCK", 0)
    with os.fdopen(os.open(path, flags), "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_BYTES:
            raise ValueError("Invalid or oversized gameplay file")
        data = source.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("Gameplay file is too large")
    value = parse_json(data)
    if not isinstance(value, dict):
        raise ValueError("Gameplay file must contain a JSON object")
    return value


def now_ms():
    return time.time_ns() // 1_000_000


def read_session(root, identity):
    try:
        session = read_object(root, f"session-{identity}.json")
    except FileNotFoundError:
        raise ValueError("The game closed this gameplay session") from None
    expiry = session.get("expiresAt")
    if session.get("id") != identity or session.get("kind") not in KINDS or type(expiry) is not int:
        raise ValueError("Invalid gameplay session")
    if expiry <= now_ms():
        raise ValueError("The gameplay session expired")
    return session


@contextmanager
def command_lock(root, identity):
    path = mailbox_path(root, f"lock-{identity}.lck")
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
    # Keep the lock file in place so parallel processes always lock the same inode.
    with os.fdopen(os.open(path, flags, 0o600), "r+b") as lock:
        if not stat.S_ISREG(os.fstat(lock.fileno()).st_mode):
            raise ValueError("Invalid gameplay lock file")
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise ValueError("A gameplay action is already in progress") from None
        yield


def publish_command(root, identity, command):
    target = mailbox_path(root, f"command-{identity}.json")
    payload = json.dumps(command, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    if len(payload) > MAX_BYTES:
        raise ValueError("The gameplay command is too large")
    descriptor, source = tempfile.mkstemp(prefix=".gameplay-", suffix=".tmp", dir=root)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
        # Publish the completed file without replacing an unread command.
        os.link(source, target)
    except FileExistsError:
        raise ValueError("The game is still processing a previous command") from None
    finally:
        Path(source).unlink(missing_ok=True)


def wait_ready(root, identity):
    deadline = time.monotonic() + READY_TIMEOUT
    while True:
        session = read_session(root, identity)
        if session.get("ready") is True:
            return
        if time.monotonic() >= deadline:
            status = session.get("status")
            detail = status if isinstance(status, str) and status.strip() else "Resume the game and close any pause menu."
            raise ValueError("The game is not ready: " + detail)
        time.sleep(.06)


def submit(root, identity, action, arguments):
    with command_lock(root, identity):
        wait_ready(root, identity)
        sequence = str(uuid.uuid4())
        publish_command(root, identity, {"id": identity, "sequence": sequence,
                                        "submittedAt": now_ms(), "action": action, "arguments": arguments})
        deadline = time.monotonic() + REPLY_TIMEOUT
        while time.monotonic() < deadline:
            try:
                reply = read_object(root, f"reply-{identity}.json")
            except FileNotFoundError:
                reply = None
            if reply is not None and reply.get("id") == identity and reply.get("sequence") == sequence:
                if type(reply.get("ok")) is not bool or not isinstance(reply.get("message"), str):
                    raise ValueError("The game returned an invalid reply")
                return reply
            try:
                read_session(root, identity)
            except ValueError:
                # Finishing can close the session immediately after writing its final reply.
                try:
                    reply = read_object(root, f"reply-{identity}.json")
                except FileNotFoundError:
                    raise ValueError("The game closed the gameplay session without replying") from None
                if (reply.get("id") == identity and reply.get("sequence") == sequence
                        and type(reply.get("ok")) is bool and isinstance(reply.get("message"), str)):
                    return reply
                raise
            time.sleep(.06)
        raise ValueError("The game did not reply. Check the session before submitting a new action.")


def tools():
    return [
        {"name": "get_gameplay_session", "description": "Read the active Minecraft gameplay session, available actions and latest observation.",
         "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
         "annotations": {"readOnlyHint": True, "openWorldHint": False}},
        {"name": "minecraft_action", "description": "Submit one action from the session's available tools. The game validates and executes the action.",
         "inputSchema": {"type": "object", "properties": {"action": {"type": "string"}, "arguments": {"type": "object"}},
                         "required": ["action", "arguments"], "additionalProperties": False},
         "annotations": {"readOnlyHint": False, "destructiveHint": True, "idempotentHint": False, "openWorldHint": False}},
        {"name": "finish_gameplay_session", "description": "Report the current task result. For AI Players the connection remains active and accepts new tasks. For AI Control this ends the session.",
         "inputSchema": {"type": "object", "properties": {"message": {"type": "string", "maxLength": 1000}},
                         "required": ["message"], "additionalProperties": False},
         "annotations": {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False}},
        {"name": "wait_for_player_request", "description": "For AI Players, wait up to 20 seconds for game messages after the last processed sequence. An empty list leaves the connection open.",
         "inputSchema": {"type": "object", "properties": {"afterSequence": {"type": "integer", "minimum": 0}},
                         "required": ["afterSequence"], "additionalProperties": False},
         "annotations": {"readOnlyHint": True, "openWorldHint": False}},
    ]


def wait_for_player_request(root, identity, after_sequence):
    deadline = time.monotonic() + 20
    while True:
        session = read_session(root, identity)
        if session["kind"] != "player":
            raise ValueError("This tool is only available for AI Player sessions")
        latest = session.get("latestSequence", 0)
        messages = [entry for entry in session.get("messages", []) if entry["sequence"] > after_sequence]
        if messages or time.monotonic() >= deadline:
            return {"messages": messages, "latestSequence": latest}
        time.sleep(.2)


def call(root, identity, name, arguments):
    if not isinstance(arguments, dict):
        raise ValueError("Tool arguments must be an object")
    if name == "get_gameplay_session" and not arguments:
        return read_session(root, identity)
    if name == "minecraft_action" and set(arguments) == {"action", "arguments"}:
        action = arguments["action"]
        parameters = arguments["arguments"]
        if isinstance(action, str) and ACTION_NAME.fullmatch(action) and action != "finish" and isinstance(parameters, dict):
            return submit(root, identity, action, parameters)
    if name == "finish_gameplay_session" and set(arguments) == {"message"}:
        message = arguments["message"]
        if isinstance(message, str) and 0 < len(message.strip()) <= 1000 and len(message) <= 1000:
            return submit(root, identity, "finish", {"message": message})
    if name == "wait_for_player_request" and set(arguments) == {"afterSequence"}:
        cursor = arguments["afterSequence"]
        if type(cursor) is int and cursor >= 0:
            return wait_for_player_request(root, identity, cursor)
    raise ValueError("Invalid gameplay tool or arguments")


def error_response(identity, code, message):
    return {"jsonrpc": "2.0", "id": identity, "error": {"code": code, "message": message}}


def handle(root, identity, request):
    if not isinstance(request, dict) or request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
        return error_response(None, -32600, "Invalid JSON-RPC request")
    if "id" not in request:
        return None
    rpc_id = request["id"]
    method = request["method"]
    params = request.get("params", {})
    if method == "initialize":
        version = params.get("protocolVersion") if isinstance(params, dict) else None
        if not isinstance(version, str) or version not in PROTOCOLS:
            version = "2025-06-18"
        result = {"protocolVersion": version, "capabilities": {"tools": {"listChanged": False}},
                  "serverInfo": {"name": "caeduex-gameplay", "version": "1.0"},
                  "instructions": "Read the exported session first. Use its action schemas and current observation. The game owns all action results."}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": tools()}
    elif method == "tools/call":
        try:
            if not isinstance(params, dict):
                raise ValueError("Invalid tool parameters")
            value = call(root, identity, params.get("name"), params.get("arguments", {}))
            result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False, allow_nan=False)}],
                      "isError": value.get("ok") is False}
        except (OSError, UnicodeError, TypeError, RecursionError):
            result = {"content": [{"type": "text", "text": "The local gameplay exchange could not be read or written."}],
                      "isError": True}
        except ValueError as failure:
            message = "Invalid gameplay JSON" if isinstance(failure, json.JSONDecodeError) else str(failure)
            result = {"content": [{"type": "text", "text": message}], "isError": True}
    else:
        return error_response(rpc_id, -32601, "Method not found")
    return {"jsonrpc": "2.0", "id": rpc_id, "result": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--session", required=True)
    args = parser.parse_args()
    try:
        identity = request_id(args.session)
        if args.root.is_symlink():
            raise ValueError("The gameplay exchange directory cannot be a symbolic link")
        root = args.root.resolve(strict=True)
        if not root.is_dir():
            raise ValueError("The gameplay exchange directory does not exist")
    except (OSError, ValueError):
        parser.error("A gameplay exchange directory and a canonical session UUID are required")

    while True:
        line = sys.stdin.buffer.readline(MAX_BYTES + 1)
        if not line:
            break
        if len(line) > MAX_BYTES:
            while line and not line.endswith(b"\n"):
                line = sys.stdin.buffer.readline(MAX_BYTES + 1)
            response = error_response(None, -32600, "Message is too large")
        else:
            try:
                response = handle(root, identity, parse_json(line))
            except (ValueError, UnicodeError, RecursionError):
                response = error_response(None, -32700, "Invalid JSON")
        if response is not None:
            sys.stdout.buffer.write(json.dumps(response, ensure_ascii=False, allow_nan=False).encode("utf-8") + b"\n")
            sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
