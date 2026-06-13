import mss
import mss.tools
import numpy as np
import cv2
import threading
from typing import Tuple, Dict, Any, Optional

class ScreenCapturer:
    def __init__(self):
        self.sct = mss.mss()
        self.monitor = self.sct.monitors[1]  # Capture primary monitor
        self.lock = threading.Lock()
        
        # Screen size cache
        self.width = self.monitor["width"]
        self.height = self.monitor["height"]
        
        # For Delta Frame optimization
        self.last_frame: Optional[np.ndarray] = None

    def get_screen_size(self) -> Tuple[int, int]:
        return self.width, self.height

    def capture_raw(self) -> np.ndarray:
        """
        Captures the screen and returns a raw BGR numpy array.
        """
        with self.lock:
            # mss captures screen fast
            screenshot = self.sct.grab(self.monitor)
            # Convert mss image format to numpy array (BGRA to BGR)
            img = np.array(screenshot)
            return cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)

    def capture_jpeg(self, quality: int = 60, scale: float = 1.0, force_full: bool = False) -> Tuple[bytes, bool, Optional[Tuple[int, int, int, int]]]:
        """
        Captures and compresses the screen to JPEG.
        Supports:
        - Downscaling (Resize) for optimization.
        - Simplified delta encoding:
          Instead of full frame, we check if there are differences.
          And returns (compressed_bytes, is_delta, bounding_box_as_x_y_w_h).
        """
        raw_frame = self.capture_raw()
        
        if scale != 1.0:
            new_w = int(self.width * scale)
            new_h = int(self.height * scale)
            raw_frame = cv2.resize(raw_frame, (new_w, new_h), interpolation=cv2.INTER_AREA)

        # Basic Delta check to optimize stream
        is_delta = False
        bbox = None
        
        if not force_full and self.last_frame is not None and self.last_frame.shape == raw_frame.shape:
            # Calculate absolute difference
            diff = cv2.absdiff(self.last_frame, raw_frame)
            gray = cv2.cvtColor(diff, cv2.COLOR_BGR2GRAY)
            _, thresh = cv2.threshold(gray, 20, 255, cv2.THRESH_BINARY)
            
            # Find non-zero regions
            non_zero_coords = np.column_stack(np.where(thresh > 0))
            if len(non_zero_coords) > 0:
                # Calculate bounding box of changes
                y_min, x_min = non_zero_coords.min(axis=0)
                y_max, x_max = non_zero_coords.max(axis=0)
                
                # Check if change is too small/big
                # If change covers less than 85% of screen, crop and send only changed region
                area_ratio = ((x_max - x_min) * (y_max - y_min)) / (raw_frame.shape[1] * raw_frame.shape[0])
                if area_ratio < 0.85 and (x_max - x_min) > 16 and (y_max - y_min) > 16:
                    crop_frame = raw_frame[y_min:y_max+1, x_min:x_max+1]
                    raw_frame = crop_frame
                    is_delta = True
                    # Store scaling invariant relative bounding box coords
                    # to map on Android end to the true screen size
                    h, w, _ = self.last_frame.shape
                    bbox = (x_min / w, y_min / h, (x_max - x_min) / w, (y_max - y_min) / h)

        # Update last frame cache
        if is_delta:
            # We must reconstruct last_frame properly on server side too
            # but to be simple and safe we can capture full periodically,
            # or just rebuild our cache. For robustness, we save the full.
            # To avoid drift, we store a shadow full frame:
            # First rebuild what the client sees
            # Actually, to make the client extremely simple, we store full frame cache as standard raw_frame
            pass
            
        # Re-capture full raw frame for next delta cache
        # For simplicity of state, cache matches full unscaled / scaled raw frame
        if scale != 1.0:
            # Cache the scaled version
            full_raw_scaled = cv2.resize(self.capture_raw(), (raw_frame.shape[1], raw_frame.shape[0]), interpolation=cv2.INTER_AREA)
            self.last_frame = full_raw_scaled
        else:
            self.last_frame = self.capture_raw()

        # Compresses to JPEG
        encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), quality]
        success, encoded_img = cv2.imencode('.jpg', raw_frame, encode_param)
        if not success:
            raise RuntimeError("JPEG conversion failed!")
            
        return encoded_img.tobytes(), is_delta, bbox
