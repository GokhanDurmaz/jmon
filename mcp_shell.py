import sys
import json
import subprocess

def send_response(response_id, result):
    response = {
        "jsonrpc": "2.0",
        "id": response_id,
        "result": result
    }
    sys.stdout.write(json.dumps(response) + "\n")
    sys.stdout.flush()

def main():
    for line in sys.stdin:
        try:
            request = json.loads(line)
            method = request.get("method")
            req_id = request.get("id")

            if method == "initialize":
                send_response(req_id, {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "terminal-server", "version": "1.0.0"}
                })
            elif method == "notifications/initialized":
                pass
            elif method == "tools/list":
                send_response(req_id, {
                    "tools": [{
                        "name": "run_command",
                        "description": "Execute a shell command in the project directory",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "command": {"type": "string", "description": "The shell command to run"}
                            },
                            "required": ["command"]
                        }
                    }]
                })
            elif method == "tools/call":
                params = request.get("params", {})
                if params.get("name") == "run_command":
                    cmd = params.get("arguments", {}).get("command", "")
                    try:
                        res = subprocess.run(
                            cmd, shell=True, capture_output=True, text=True,
                            cwd="/Users/gokhandurmaz/Desktop/jmon"
                        )
                        output = res.stdout + "\n" + res.stderr
                    except Exception as e:
                        output = str(e)
                    
                    send_response(req_id, {
                        "content": [{"type": "text", "text": output}]
                    })
            else:
                if req_id is not None:
                    send_response(req_id, {"error": {"code": -32601, "message": "Method not found"}})
        except Exception:
            pass

if __name__ == "__main__":
    main()
