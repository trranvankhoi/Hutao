import sys
import os
import signal
import time
import logging

# Ensure local imports work correctly regardless of running from root
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from capture import ScreenCapturer
from stream import VideoStreamer
from control import ControlServer

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s'
)
logger = logging.getLogger("RemoteDesktopServer")

def main():
    print("==================================================")
    print("      ULTRA-LOW LATENCY REMOTE DESKTOP SERVER     ")
    print("==================================================")
    
    # Simple CLI customization
    password = input("Set session pairing password (default: Admin123): ").strip()
    if not password:
        password = "Admin123"
        
    logger.info(f"Initializing remote desktop services using password: {password}")
    
    # Core Subsystems Setup
    capturer = ScreenCapturer()
    streamer = VideoStreamer(port=5001, capturer=capturer)
    server = ControlServer(port=5000, password=password)
    
    # Establish cross-link references
    server.set_streamer(streamer)
    
    # Start background loop listeners
    streamer.start()
    server.start()
    
    logger.info("Remote services started successfully. Waiting for Android Client pairings...")
    print("Press Ctrl+C to terminate the server safely.")
    
    # Keep main thread alive
    try:
        while True:
            time.sleep(1.0)
    except KeyboardInterrupt:
        logger.info("Received termination signal. Shutting down servers gracefully...")
    finally:
        server.stop()
        streamer.stop()
        logger.info("Server subsystems closed successfully.")

if __name__ == "__main__":
    main()
