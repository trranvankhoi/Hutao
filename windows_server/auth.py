import hashlib
import time
import secrets
from typing import Dict, Tuple

class AuthManager:
    def __init__(self, password: str, timeout_seconds: int = 3600):
        self.password = password
        self.timeout_seconds = timeout_seconds
        # Store valid sessions: token -> (expiry_time, client_address)
        self.active_sessions: Dict[str, Tuple[float, str]] = {}
        # Simple backoff/brute-force protection: IP -> (failed_attempts, lockout_until)
        self.login_attempts: Dict[str, Tuple[int, float]] = {}

    def get_password_hash(self) -> str:
        return hashlib.sha256(self.password.encode('utf-8')).hexdigest()

    def login(self, client_ip: str, client_password_hash: str) -> Tuple[bool, str]:
        """
        Validate password hash. Returns (success, token_or_error_msg).
        """
        now = time.time()
        
        # Check lockout
        if client_ip in self.login_attempts:
            attempts, lockout_until = self.login_attempts[client_ip]
            if now < lockout_until:
                remaining = int(lockout_until - now)
                return False, f"Locked out. Try again in {remaining}s."
        
        # Verify hash
        server_hash = self.get_password_hash()
        if secrets.compare_digest(server_hash, client_password_hash):
            # Clear failed attempts on success
            if client_ip in self.login_attempts:
                self.login_attempts.pop(client_ip)
                
            # Create session token
            token = secrets.token_hex(16)
            expiry = now + self.timeout_seconds
            self.active_sessions[token] = (expiry, client_ip)
            return True, token
        else:
            # Handle failure & increment brute-force count
            attempts, lockout_until = self.login_attempts.get(client_ip, (0, 0.0))
            attempts += 1
            if attempts >= 5:
                # Lockout for 30s * (attempts - 4)
                lockout_duration = 30 * (attempts - 4)
                lockout_until = now + lockout_duration
                self.login_attempts[client_ip] = (attempts, lockout_until)
                return False, f"Too many failed attempts. Locked out for {lockout_duration}s."
            else:
                self.login_attempts[client_ip] = (attempts, 0.0)
                return False, f"Incorrect password. Attempt {attempts}/5."

    def validate_session(self, token: str, client_ip: str) -> bool:
        """
        Validates token matches current client and hasn't expired.
        """
        if not token or token not in self.active_sessions:
            return False
            
        expiry, saved_ip = self.active_sessions[token]
        if time.time() > expiry:
            # Remove expired
            self.active_sessions.pop(token, None)
            return False
            
        # Optional: bind to IP address for security
        if saved_ip != client_ip:
            return False
            
        return True

    def logout(self, token: str):
        if token in self.active_sessions:
            self.active_sessions.pop(token)
