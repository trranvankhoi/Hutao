import socket
import threading
import json
import time
import logging
from typing import Optional, Tuple
from .auth import AuthManager
from .control_manager import ControlManager
from .stream import VideoStreamer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ControlServer")

class ControlServer:
    """
    Core TCP controller server. Runs on port 5000.
    Handles client pairing, authorization verification, input routing and settings adjustments.
    """
    def __init__(self, port: int = 5000, password: str = "Admin123"):
        self.port = port
        self.auth = AuthManager(password)
        self.control = ControlManager()
        self.streamer: Optional[VideoStreamer] = None
        
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.running = False
        
        # Discovery Broadcast Handler thread
        self.discovery_running = False
        self.discovery_thread: Optional[threading.Thread] = None

    def set_streamer(self, streamer: VideoStreamer):
        self.streamer = streamer

    def start(self):
        self.running = True
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(5)
        
        # Thread for TCP acceptance
        self.tcp_thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.tcp_thread.start()
        
        # Thread for UDP Peer Discovery (LAN broadcast)
        self.discovery_running = True
        self.discovery_thread = threading.Thread(target=self._discovery_loop, daemon=True)
        self.discovery_thread.start()
        
        logger.info(f"Control TCP Server initialized on Port {self.port}")
        logger.info(f"Discovery Broadcast Listener enabled on UDP Port 5002")

    def stop(self):
        self.running = False
        self.discovery_running = False
        self.sock.close()
        logger.info("Control Server stopped")

    def _discovery_loop(self):
        """
        Listens internally for UDP Broadcast pings from Android clients on LAN.
        Responding with self configuration facilitates fully automated client pairing.
        """
        disc_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        disc_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            disc_sock.bind(('0.0.0.0', 5002))
        except Exception as e:
            logger.error(f"Failed to bind discovery socket: {e}")
            return
            
        disc_sock.settimeout(1.0)
        
        while self.discovery_running:
            try:
                data, addr = disc_sock.recvfrom(1024)
                message = data.decode("utf-8")
                if "REMOTE_DISCOVER_REQUEST" in message:
                    response = {
                        "type": "REMOTE_DISCOVER_RESPONSE",
                        "server_name": socket.gethostname(),
                        "tcp_port": self.port,
                        "status": "ready"
                    }
                    disc_sock.sendto(json.dumps(response).encode("utf-8"), addr)
            except socket.timeout:
                continue
            except Exception as e:
                logger.error(f"Discovery loop issue encountered: {e}")
                time.sleep(1.0)
        disc_sock.close()

    def _accept_loop(self):
        while self.running:
            try:
                client_sock, client_addr = self.sock.accept()
                logger.info(f"Incoming TCP client connection requested from {client_addr}")
                # Dispatch handler per client connection
                thread = threading.Thread(
                    target=self._handle_client,
                    args=(client_sock, client_addr),
                    daemon=True
                )
                thread.start()
            except Exception as e:
                if self.running:
                    logger.error(f"TCP Accept issue encountered: {e}")
                break

    def _handle_client(self, client_sock: socket.socket, client_addr: Tuple[str, int]):
        """
        Validates client commands, parses structured stream requests,
        and routes actions to control interface.
        """
        client_ip = client_addr[0]
        authenticated = False
        session_token = ""
        
        client_sock.settimeout(30.0) # disconnect inactive channels
        
        # Buffer to accumulate incoming text streams gracefully
        buffer = ""
        
        while self.running:
            try:
                data = client_sock.recv(4096)
                if not data:
                    logger.info(f"Client disconnected gracefully: {client_addr}")
                    break
                    
                buffer += data.decode("utf-8")
                
                # Check for completeness of JSON commands terminated by newlines
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    if not line.strip():
                        continue
                        
                    try:
                        req = json.loads(line)
                    except json.JSONDecodeError:
                        self._send_response(client_sock, {"status": "error", "message": "Malformed JSON payload"})
                        continue
                        
                    req_type = req.get("type")
                    
                    # 1. Verification / Authentication Path
                    if req_type == "auth_login":
                        pass_hash = req.get("password_hash", "")
                        success, token_or_err = self.auth.login(client_ip, pass_hash)
                        if success:
                            authenticated = True
                            session_token = token_or_err
                            self._send_response(client_sock, {
                                "status": "success",
                                "message": "Authenticated successfully",
                                "token": session_token
                            })
                            logger.info(f"Successfully handshake/paired client from: {client_ip}")
                        else:
                            self._send_response(client_sock, {
                                "status": "error",
                                "message": token_or_err
                            })
                        continue
                        
                    # Remaining flows require authenticated state
                    if not authenticated:
                        self._send_response(client_sock, {
                            "status": "error",
                            "message": "Authentication required"
                        })
                        continue
                        
                    # Validate Token bound inside commands
                    cmd_token = req.get("token")
                    if not self.auth.validate_session(cmd_token, client_ip):
                        self._send_response(client_sock, {
                            "status": "error",
                            "message": "Invalid or expired session token"
                        })
                        # Shut active TCP stream
                        break
                        
                    # 2. Setup UDP video streaming flow target
                    if req_type == "register_video_stream":
                        udp_port = req.get("udp_port", 5001)
                        if self.streamer:
                            self.streamer.register_client(client_ip, udp_port)
                            self._send_response(client_sock, {
                                "status": "success",
                                "message": f"UDP screen streaming configured targeting port {udp_port}"
                            })
                        else:
                            self._send_response(client_sock, {
                                "status": "error",
                                "message": "Streaming subsystem is offline"
                            })
                        continue
                        
                    # 3. Handle Streaming parameter changes
                    if req_type == "update_stream_settings":
                        quality = req.get("quality", 60)
                        fps = req.get("fps", 30)
                        scale = req.get("scale", 1.0)
                        if self.streamer:
                            self.streamer.update_settings(quality, fps, scale)
                            self._send_response(client_sock, {
                                "status": "success",
                                "info": "Settings applied"
                            })
                        continue
                        
                    # 4. Handle System Control Command Routing
                    # Redirects mouse details, keys, locks, restarts
                    response = self.control.handle_command(req)
                    self._send_response(client_sock, response)
                    
            except socket.timeout:
                # No activities over 30s can hold, check ping heartbeats
                continue
            except Exception as e:
                logger.error(f"Client loop terminated on error: {e}")
                break
                
        client_sock.close()
        # Revoke token on exit
        if session_token:
            self.auth.logout(session_token)

    def _send_response(self, sock: socket.socket, payload: dict):
        try:
            # Suffix with newline for demarcating framing boundary
            data = (json.dumps(payload) + "\n").encode("utf-8")
            sock.sendall(data)
        except Exception:
            pass
