import socket
import struct
import math
import time
import logging
import threading
from typing import List, Optional, Tuple
from .capture import ScreenCapturer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("VideoStreamer")

class VideoStreamer:
    """
    Manages UDP streaming of desktop content.
    Includes adaptive quality controls, frame chunking (fragmentation), and rates.
    """
    # Max size we transmit in single UDP packet safely within typical MTU
    # 1300 leaves buffer from ethernet MTU of 1500 to handle overhead
    PACKET_MAX_SIZE = 1300 
    
    # Packet header format:
    # H -> unsigned short: frame_id (0-65535)
    # H -> unsigned short: packet_id (0-65535)
    # H -> unsigned short: total_packets (0-65535)
    # ? -> bool: is_delta (True/False)
    # f, f, f, f -> 4 floats: relative bounding box x, y, width, height (only relevant if is_delta)
    HEADER_FORMAT = "!HHH?ffff"
    HEADER_SIZE = struct.calcsize(HEADER_FORMAT)

    def __init__(self, port: int = 5001, capturer: Optional[ScreenCapturer] = None):
        self.port = port
        self.capturer = capturer or ScreenCapturer()
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        
        # Stream configuration
        self.jpeg_quality = 60
        self.fps = 30
        self.scale = 1.0 # 1.0 is full, 0.5 is half dimensions, etc.
        
        # State
        self.running = False
        self.frame_counter = 0
        self.client_address_and_token: Optional[Tuple[str, int]] = None
        self.thread = None
        self.current_fps = 0

    def start(self):
        """
        Starts the background streaming thread.
        """
        if self.running:
            return
        self.running = True
        self.thread = threading.Thread(target=self._stream_loop, daemon=True)
        self.thread.start()
        logger.info(f"Video streaming server initialized on Port {self.port}")

    def stop(self):
        self.running = False
        if self.thread:
            self.thread.join(timeout=1.0)
        logger.info("Video streaming server stopped")

    def register_client(self, ip: str, port: int):
        """
        Registers active client destination for streaming.
        """
        self.client_address_and_token = (ip, port)
        logger.info(f"Streaming registered destination to client: {ip}:{port}")

    def update_settings(self, quality: int, fps: int, scale: float):
        """
        Dynamically adapts flow settings based on client request.
        """
        self.jpeg_quality = max(10, min(100, quality))
        self.fps = max(1, min(60, fps))
        self.scale = max(0.2, min(1.0, scale))
        logger.info(f"Adaptive adjustments applied: Quality={self.jpeg_quality}, FPS={self.fps}, Scale={self.scale}")

    def _stream_loop(self):
        """
        Indefinite loop that generates frames and transmits them in fragment flows.
        """
        fps_start_time = time.time()
        fps_count = 0
        while self.running:
            start_time = time.time()
            
            if self.client_address_and_token is not None:
                try:
                    # Capture & Encapsulate Desktop
                    # Send Full Frame every 45 frames (roughly 1.5 seconds) to self-heal missing delta packets
                    force_full = (self.frame_counter % 45 == 0)
                    
                    frame_bytes, is_delta, bbox = self.capturer.capture_jpeg(
                        quality=self.jpeg_quality,
                        scale=self.scale,
                        force_full=force_full
                    )
                    
                    self._send_fragmented_frame(frame_bytes, is_delta, bbox)
                    self.frame_counter = (self.frame_counter + 1) % 65536
                    
                    fps_count += 1
                    now = time.time()
                    if now - fps_start_time >= 1.0:
                        self.current_fps = int(fps_count / (now - fps_start_time))
                        fps_count = 0
                        fps_start_time = now
                except Exception as e:
                    logger.error(f"Failed to capture or stream frame: {e}")
            else:
                self.current_fps = 0
            
            # Rate limiter
            elapsed = time.time() - start_time
            sleep_time = (1.0 / self.fps) - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)

    def _send_fragmented_frame(self, data: bytes, is_delta: bool, bbox: Optional[Tuple[float, float, float, float]]):
        """
        Splits complete image raw byte buffer into safe transmittable chunks.
        """
        if self.client_address_and_token is None:
            return
            
        data_len = len(data)
        total_packets = math.ceil(data_len / self.PACKET_MAX_SIZE)
        
        # Bounding boxes for Delta regions
        bx, by, bw, bh = bbox if (is_delta and bbox is not None) else (0.0, 0.0, 0.0, 0.0)

        for i in range(total_packets):
            start_offset = i * self.PACKET_MAX_SIZE
            end_offset = min(start_offset + self.PACKET_MAX_SIZE, data_len)
            chunk = data[start_offset:end_offset]
            
            # Format header: frame_id, packet_id, total_packets, is_delta, x, y, w, h
            header = struct.pack(
                self.HEADER_FORMAT,
                self.frame_counter,
                i,
                total_packets,
                is_delta,
                bx, by, bw, bh
            )
            
            full_payload = header + chunk
            try:
                self.sock.sendto(full_payload, self.client_address_and_token)
            except Exception as e:
                # Occurs if remote buffer overflows, ignore gracefully to verify UDP throughput
                pass
