import pyautogui
import pynput
import ctypes
import os
import psutil
from typing import Dict, Any

# Configure PyAutoGUI precautions
pyautogui.FAILSAFE = False

class ControlManager:
    """
    Executes input simulation tasks received from Command Channel
    with high platform compliance.
    """
    def __init__(self):
        # Using pynput handles some keyboard edge cases nicely,
        # fallback to pyautogui for coordinates and mouse.
        self.keyboard_controller = pynput.keyboard.Controller()
        self.mouse_controller = pynput.mouse.Controller()
        self.enabled = True

    def handle_command(self, cmd: Dict[str, Any]) -> Dict[str, Any]:
        """
        Processes command safely, returns result dict.
        """
        if not self.enabled:
            return {"status": "error", "message": "Controls are currently disabled on the server"}

        cmd_type = cmd.get("type")
        
        try:
            if cmd_type == "mouse_move":
                # Raw scaling values from client are 0.0 - 1.0 (relative)
                rx = cmd.get("x", 0.0)
                ry = cmd.get("y", 0.0)
                
                # Fetch absolute system screens metrics
                w, h = pyautogui.size()
                target_x = int(rx * w)
                target_y = int(ry * h)
                
                # Ensure values reside in boundary limits
                target_x = max(0, min(w - 1, target_x))
                target_y = max(0, min(h - 1, target_y))
                
                pyautogui.moveTo(target_x, target_y)
                return {"status": "success", "info": f"Moved to {target_x}, {target_y}"}
                
            elif cmd_type == "mouse_click":
                button = cmd.get("button", "left")
                # Perform tap click at current mouse positions
                if button == "left":
                    pyautogui.click()
                elif button == "right":
                    pyautogui.rightClick()
                elif button == "double":
                    pyautogui.doubleClick()
                return {"status": "success", "info": f"Performed click event {button}"}
                
            elif cmd_type == "mouse_scroll":
                # Scroll value: positive (up) / negative (down)
                scroll_value = cmd.get("value", 0)
                # PyAutoGUI handles windows scrolls elegantly
                pyautogui.scroll(int(scroll_value * 120))
                return {"status": "success", "info": f"Scrolled distance {scroll_value}"}
                
            elif cmd_type == "keyboard_input":
                text = cmd.get("text", "")
                key_code = cmd.get("key_code") # for system actions (like backspace, enter, etc.)
                
                if text:
                    # Input Unicode text directly
                    # standard pyautogui.write is slow and struggles with international characters.
                    # Instead, we feed keys into keyboard controller.
                    self.keyboard_controller.type(text)
                    return {"status": "success", "info": f"Typed text payload: {text}"}
                elif key_code:
                    # Special key presses like backspace, enter
                    self._press_special_key(key_code)
                    return {"status": "success", "info": f"Pressed code {key_code}"}
                    
            elif cmd_type == "shutdown":
                # Executes safe ACPI system level exit shutdown
                os.system("shutdown /s /t 1")
                return {"status": "success", "info": "Shutdown sequence initiated"}
                
            elif cmd_type == "restart":
                os.system("shutdown /r /t 1")
                return {"status": "success", "info": "Restart sequence initiated"}
                
            elif cmd_type == "lock_screen":
                # Standard Win+L user lock sequence
                ctypes.windll.user32.LockWorkStation()
                return {"status": "success", "info": "System screen status adjusted to locked"}
                
            return {"status": "error", "message": f"Unsupported or unrecognized input directive '{cmd_type}'"}
            
        except Exception as e:
            return {"status": "error", "message": f"Execution error: {str(e)}"}

    def _press_special_key(self, key_code: str):
        """
        Converts custom keyboard action codes to Windows inputs.
        """
        code = key_code.lower()
        if code == "win+l":
            import ctypes
            ctypes.windll.user32.LockWorkStation()
            return
        elif code in ("ctrl+alt+del", "ctrl+alt+delete"):
            # Direct Windows OS security restrictions prevent simulating true Ctrl+Alt+Del signal,
            # but we can simulate Ctrl+Shift+Esc to directly trigger the Windows Task Manager.
            try:
                with self.keyboard_controller.pressed(pynput.keyboard.Key.ctrl, pynput.keyboard.Key.shift):
                    self.keyboard_controller.press(pynput.keyboard.Key.esc)
                    self.keyboard_controller.release(pynput.keyboard.Key.esc)
            except Exception:
                pass
            return

        key_map = {
            "enter": pynput.keyboard.Key.enter,
            "backspace": pynput.keyboard.Key.backspace,
            "space": pynput.keyboard.Key.space,
            "tab": pynput.keyboard.Key.tab,
            "escape": pynput.keyboard.Key.esc,
            "up": pynput.keyboard.Key.up,
            "down": pynput.keyboard.Key.down,
            "left": pynput.keyboard.Key.left,
            "right": pynput.keyboard.Key.right,
        }
        
        target_key = key_map.get(key_code.lower())
        if target_key:
            self.keyboard_controller.press(target_key)
            self.keyboard_controller.release(target_key)
