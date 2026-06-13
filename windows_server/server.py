import sys
import os
import socket
import struct
import json
import time
import logging
import threading
import queue
import winreg
import ctypes
from typing import Optional, Tuple

import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext
from PIL import Image, ImageDraw

# Add current path to sys.path to locate dependencies
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from capture import ScreenCapturer
from stream import VideoStreamer
from control import ControlServer

# Configure Logger
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s'
)
logger = logging.getLogger("RemoteDesktopServer")

# Queue for routing logs thread-safely to Tkinter UI
log_queue = queue.Queue()

class QueueLogHandler(logging.Handler):
    """Pipes background log entries into a queue for Tkinter safety."""
    def __init__(self, q):
        super().__init__()
        self.q = q

    def emit(self, record):
        try:
            msg = self.format(record)
            self.q.put(msg)
        except Exception:
            self.handleError(record)


class PCRemoteServerApp:
    def __init__(self, root):
        self.root = root
        self.root.title("PCRemoteServer - Immersive Control Gateway")
        self.root.geometry("640x580")
        self.root.resizable(False, False)
        self.root.configure(bg="#1A1C1E")
        
        # Enable Dark Titlebar header on Windows (if supported)
        try:
            hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id())
            # DWMWA_USE_IMMERSIVE_DARK_MODE = 20
            ctypes.windll.dwmapi.DwmSetWindowAttribute(
                hwnd, 20, ctypes.byref(ctypes.c_int(1)), ctypes.sizeof(ctypes.c_int(1))
            )
        except Exception:
            pass

        # State configurations
        self.reg_key_name = "PCRemoteServer"
        self.current_password = "Admin123"
        self.tcp_port = 5000
        self.udp_port = 5001
        self.is_streaming_enabled = True
        self.is_controls_enabled = True
        self.tray_icon: Optional[pystray.Icon] = None
        self.is_shutting_down = False
        
        # Subsystems references
        self.capturer: Optional[ScreenCapturer] = None
        self.streamer: Optional[VideoStreamer] = None
        self.server: Optional[ControlServer] = None
        
        # Tkinter style bindings
        self.setup_styles()
        
        # Initialize Core Subsystems
        self.start_backend_services()

        # Build UI Screen layouts
        self.build_ui()
        
        # Hook Window protocol to minimize to system tray instead of exiting
        self.root.protocol('WM_DELETE_WINDOW', self.minimize_to_tray)

        # Route logger handlers into Tkinter log terminal
        q_handler = QueueLogHandler(log_queue)
        q_handler.setFormatter(logging.Formatter('%H:%M:%S [%(levelname)s] %(message)s'))
        logging.getLogger().addHandler(q_handler)
        
        # Launch scheduling loops (System Stats + Log polling)
        self.poll_system_stats()
        self.poll_log_queue()
        
        # Setup background System Tray icon
        self.setup_tray()

    def setup_styles(self):
        """Pre-configures modern styling accents in Tkinter."""
        style = ttk.Style()
        style.theme_use('clam')
        
        # Dark Mode Global Theme Setup
        style.configure(".", background="#1A1C1E", foreground="#E2E2E6", fieldbackground="#333538")
        style.configure("TFrame", background="#1A1C1E")
        style.configure("Card.TFrame", background="#333538", borderwidth=1, relief="solid")
        
        style.configure("TLabel", background="#1A1C1E", foreground="#E2E2E6", font=("Segoe UI", 10))
        style.configure("Title.TLabel", background="#1A1C1E", foreground="#D0BCFF", font=("Segoe UI", 16, "bold"))
        style.configure("Sub.TLabel", background="#1A1C1E", foreground="#909094", font=("Segoe UI", 9))
        style.configure("CardTitle.TLabel", background="#333538", foreground="#E2E2E6", font=("Segoe UI", 11, "bold"))
        style.configure("CardValue.TLabel", background="#333538", foreground="#D0BCFF", font=("Segoe UI", 13, "bold"))
        style.configure("CardMetric.TLabel", background="#333538", foreground="#909094", font=("Segoe UI", 9))
        
        # Buttons Style Configuration
        style.configure("TButton", background="#44474E", foreground="#E2E2E6", borderwidth=0, font=("Segoe UI", 9, "bold"))
        style.map("TButton",
            background=[('active', '#D0BCFF'), ('pressed', '#381E72')],
            foreground=[('active', '#1A1C1E'), ('pressed', '#E2E2E6')]
        )
        
        style.configure("Primary.TButton", background="#D0BCFF", foreground="#381E72", borderwidth=0, font=("Segoe UI", 10, "bold"))
        style.map("Primary.TButton",
            background=[('active', '#381E72'), ('pressed', '#1A1C1E')],
            foreground=[('active', '#E2E2E6'), ('pressed', '#D0BCFF')]
        )
        
        style.configure("Alert.TButton", background="#BA1A1A", foreground="#FFFFFF", borderwidth=0, font=("Segoe UI", 10, "bold"))
        style.map("Alert.TButton",
            background=[('active', '#FFB4AB'), ('pressed', '#BA1A1A')],
            foreground=[('active', '#600004'), ('pressed', '#FFFFFF')]
        )
        
        # Checkbox & Entry styling
        style.configure("TCheckbutton", background="#1A1C1E", foreground="#E2E2E6", font=("Segoe UI", 10))
        style.map("TCheckbutton",
            background=[('active', '#1A1C1E')],
            indicatorcolor=[('selected', '#D0BCFF'), ('!selected', '#44474E')]
        )

    def start_backend_services(self):
        """Initializes and runs control servers and video streams."""
        try:
            self.capturer = ScreenCapturer()
            self.streamer = VideoStreamer(port=self.udp_port, capturer=self.capturer)
            self.server = ControlServer(port=self.tcp_port, password=self.current_password)
            
            # Hook dependencies
            self.server.set_streamer(self.streamer)
            self.server.control.enabled = self.is_controls_enabled
            
            # Start processes in daemon threads
            if self.is_streaming_enabled:
                self.streamer.start()
            self.server.start()
            
            logger.info("PC Remote Server subsystems started smoothly.")
        except Exception as e:
            logger.error(f"Failed to boot server services: {e}")
            messagebox.showerror("Boot Error", f"Failed to instantiate server channels: {e}")

    def restart_backend_services(self):
        """Safely stops active servers and restarts them with updated configuration ports."""
        logger.info("Applying dynamic communication port adjustments...")
        try:
            # Shutdown active elements
            if self.server:
                self.server.stop()
            if self.streamer:
                self.streamer.stop()
                
            # Sleep slightly to let ports release fully
            time.sleep(0.5)
            
            # Recreate with new values
            self.streamer = VideoStreamer(port=self.udp_port, capturer=self.capturer)
            self.server = ControlServer(port=self.tcp_port, password=self.current_password)
            self.server.set_streamer(self.streamer)
            self.server.control.enabled = self.is_controls_enabled
            
            # Run services
            if self.is_streaming_enabled:
                self.streamer.start()
            self.server.start()
            
            logger.info(f"Port changes applied: TCP={self.tcp_port}, UDP={self.udp_port}")
            messagebox.showinfo("Success", f"Server restarted successfully.\nTCP Port: {self.tcp_port}\nUDP Port: {self.udp_port}")
        except Exception as e:
            logger.error(f"Failed to reset server communication: {e}")
            messagebox.showerror("Reset Error", f"Failed to modify socket channels: {e}")

    def build_ui(self):
        """Draws the beautiful, structured Immersive GUI layout."""
        # Top Header Status Bar Frame
        header_frame = ttk.Frame(self.root, padding="16 12 16 12")
        header_frame.pack(fill="x", side="top")
        
        title_label = ttk.Label(header_frame, text="Unified Remote Control Portal", style="Title.TLabel")
        title_label.grid(row=0, column=0, sticky="w")
        
        desc_label = ttk.Label(header_frame, text="Active background server for pairings & telemetry stream", style="Sub.TLabel")
        desc_label.grid(row=1, column=0, sticky="w", pady=(2, 0))

        # Divider Separator
        sep = ttk.Separator(self.root, orient="horizontal")
        sep.pack(fill="x", px=16)

        # Main Workspace: 2 Column panels
        main_body = ttk.Frame(self.root, padding=16)
        main_body.pack(fill="both", expand=True)

        left_panel = ttk.Frame(main_body)
        left_panel.pack(fill="both", side="left", expand=True, padx=(0, 8))

        right_panel = ttk.Frame(main_body)
        right_panel.pack(fill="both", side="right", expand=True, padx=(8, 0))

        # --- LEFT PANEL: Status and Live Gauges Cards ---
        # Card 1: Network & Connection
        self.net_card = ttk.Frame(left_panel, style="Card.TFrame", padding=12)
        self.net_card.pack(fill="x", pady=(0, 10))
        
        ttk.Label(self.net_card, text="GATEWAY HOST ADAPTERS", style="CardTitle.TLabel").pack(anchor="w")
        self.ip_value_lbl = ttk.Label(self.net_card, text=self.get_active_ips(), style="CardValue.TLabel")
        self.ip_value_lbl.pack(anchor="w", pady=4)
        self.status_lbl = ttk.Label(self.net_card, text="Status: Ready (Standby)", style="CardMetric.TLabel")
        self.status_lbl.pack(anchor="w")

        # Card 2: Stream telemetry
        self.stream_card = ttk.Frame(left_panel, style="Card.TFrame", padding=12)
        self.stream_card.pack(fill="x", pady=(0, 10))
        
        ttk.Label(self.stream_card, text="DELIVERY FEED RATE", style="CardTitle.TLabel").pack(anchor="w")
        self.fps_value_lbl = ttk.Label(self.stream_card, text="0 FPS", style="CardValue.TLabel")
        self.fps_value_lbl.pack(anchor="w", pady=4)
        self.stream_state_lbl = ttk.Label(self.stream_card, text="Stream Loop Active", style="CardMetric.TLabel")
        self.stream_state_lbl.pack(anchor="w")

        # Card 3: PC Metrics (CPU & RAM Load)
        self.metrics_card = ttk.Frame(left_panel, style="Card.TFrame", padding=12)
        self.metrics_card.pack(fill="both", expand=True)
        
        ttk.Label(self.metrics_card, text="SYSTEM COMPUTATION LOAD", style="CardTitle.TLabel").pack(anchor="w")
        
        self.cpu_load_lbl = ttk.Label(self.metrics_card, text="CPU Usage: --%", style="CardValue.TLabel")
        self.cpu_load_lbl.pack(anchor="w", pady=(8, 4))
        
        self.ram_load_lbl = ttk.Label(self.metrics_card, text="RAM Usage: --%", style="CardValue.TLabel")
        self.ram_load_lbl.pack(anchor="w", pady=4)

        # --- RIGHT PANEL: Configuration Panel ---
        # Card 4: Settings Fields
        settings_card = ttk.Frame(right_panel, style="Card.TFrame", padding=14)
        settings_card.pack(fill="both", expand=True)
        
        ttk.Label(settings_card, text="PORT & AUTH CREDENTIALS", style="CardTitle.TLabel").pack(anchor="w", pady=(0, 10))
        
        # Password
        ttk.Label(settings_card, text="Session Pairing Password:", style="TLabel").pack(anchor="w")
        self.pass_entry = ttk.Entry(settings_card, font=("Segoe UI", 10))
        self.pass_entry.insert(0, self.current_password)
        self.pass_entry.pack(fill="x", pady=(4, 10))
        
        # Ports Layout Config
        ports_row = ttk.Frame(settings_card)
        ports_row.pack(fill="x", pady=(0, 10))
        
        tcp_col = ttk.Frame(ports_row)
        tcp_col.pack(side="left", fill="x", expand=True, padx=(0, 6))
        ttk.Label(tcp_col, text="TCP Control Port:", style="TLabel").pack(anchor="w")
        self.tcp_entry = ttk.Entry(tcp_col, font=("Segoe UI", 10))
        self.tcp_entry.insert(0, str(self.tcp_port))
        self.tcp_entry.pack(fill="x", pady=4)

        udp_col = ttk.Frame(ports_row)
        udp_col.pack(side="right", fill="x", expand=True, padx=(6, 0))
        ttk.Label(udp_col, text="UDP Stream Port:", style="TLabel").pack(anchor="w")
        self.udp_entry = ttk.Entry(udp_col, font=("Segoe UI", 10))
        self.udp_entry.insert(0, str(self.udp_port))
        self.udp_entry.pack(fill="x", pady=4)

        # Apply Configuration Port Button
        apply_btn = ttk.Button(settings_card, text="SAVE & APPLY RECONIG", command=self.apply_configurations, style="Primary.TButton")
        apply_btn.pack(fill="x", pady=(0, 14))

        # Checkboxes/Toggles for features
        self.stream_chk_var = tk.BooleanVar(value=self.is_streaming_enabled)
        self.stream_chk = ttk.Checkbutton(
            settings_card, text="Enable Desktop Streamer", 
            variable=self.stream_chk_var, command=self.toggle_streaming
        )
        self.stream_chk.pack(anchor="w", pady=4)

        self.control_chk_var = tk.BooleanVar(value=self.is_controls_enabled)
        self.control_chk = ttk.Checkbutton(
            settings_card, text="Enable Input Controls (Mouse/Keys)", 
            variable=self.control_chk_var, command=self.toggle_input_controls
        )
        self.control_chk.pack(anchor="w", pady=4)

        self.auto_chk_var = tk.BooleanVar(value=self.check_autostart_enabled())
        self.auto_chk = ttk.Checkbutton(
            settings_card, text="Start Automatically with Windows", 
            variable=self.auto_chk_var, command=self.toggle_autostart
        )
        self.auto_chk.pack(anchor="w", pady=4)

        # Divider
        ttk.Separator(right_panel, orient="horizontal").pack(fill="x", pady=10)

        # --- BOTTOM WORKSPACE: Activity Logs console Terminal ---
        bottom_frame = ttk.Frame(self.root, padding="16 0 16 16")
        bottom_frame.pack(fill="both", expand=True, side="bottom")

        ttk.Label(bottom_frame, text="CORE TELEMETRY LOGS TERMINAL", style="TLabel", font=("Segoe UI", 8, "bold")).pack(anchor="w", pady=(0, 4))
        
        self.log_text = scrolledtext.ScrolledText(
            bottom_frame, height=5, bg="#111318", fg="#E2E2E6", 
            insertbackground="#D0BCFF", font=("Consolas", 9),
            state="disabled", borderwidth=0, highlightthickness=1,
            highlightbackground="#44474E"
        )
        self.log_text.pack(fill="both", expand=True)

        # Action Buttons Tray
        btn_tray = ttk.Frame(bottom_frame)
        btn_tray.pack(fill="x", pady=(10, 0))
        
        hide_btn = ttk.Button(btn_tray, text="MINIMIZE BACKGROUND (TRAY)", command=self.minimize_to_tray)
        hide_btn.pack(side="left", padx=(0, 8))
        
        exit_btn = ttk.Button(btn_tray, text="TERMINATE PORTAL", command=self.quit_server, style="Alert.TButton")
        exit_btn.pack(side="right")

    def get_active_ips(self) -> str:
        """Fetches all valid LAN IP addresses on the machine."""
        ips = []
        try:
            host_name = socket.gethostname()
            # Loopback bypass filtering
            ips = [ip for ip in socket.gethostbyname_ex(host_name)[2] if not ip.startswith("127.")]
        except Exception:
            pass
        if not ips:
            try:
                # Fallback UDP channel metric method
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.connect(("8.8.8.8", 80))
                ips.append(s.getsockname()[0])
                s.close()
            except Exception:
                ips.append("127.0.0.1")
        return ", ".join(ips[:2])

    def poll_system_stats(self):
        """Periodically requests system resource percentages."""
        if self.is_shutting_down:
            return
            
        import psutil
        try:
            # Gather statistics safely
            cpu_val = psutil.cpu_percent()
            ram_val = psutil.virtual_memory().percent
            
            self.cpu_load_lbl.configure(text=f"CPU Usage: {cpu_val:.1f}%")
            self.ram_load_lbl.configure(text=f"RAM Usage: {ram_val:.1f}%")
            
            # Read real active streaming metrics from Streamer
            if self.streamer and self.streamer.running:
                active_fps = self.streamer.current_fps
                self.fps_value_lbl.configure(text=f"{active_fps} FPS")
            else:
                self.fps_value_lbl.configure(text="0 FPS (Off)")
                
            # Update current connection details
            if self.server and any(t.is_alive() for t in threading.enumerate() if "handle_client" in getattr(t, "name", "")):
                # Client connected
                self.status_lbl.configure(text="Status: Streaming Active Controller", foreground="#B4FF98")
            else:
                self.status_lbl.configure(text="Status: Ready (Standby)", foreground="#E2E2E6")
                
        except Exception as e:
            print(f"Stats polling issue: {e}")
            
        # Schedule next update in 1 second
        self.root.after(1000, self.poll_system_stats)

    def poll_log_queue(self):
        """Polls log queue and flushes content thread-safely into Tkinter ScrolledText."""
        if self.is_shutting_down:
            return
            
        while not log_queue.empty():
            try:
                msg = log_queue.get_nowait()
                self.log_text.configure(state="normal")
                self.log_text.insert("end", msg + "\n")
                self.log_text.see("end")
                self.log_text.configure(state="disabled")
            except queue.Empty:
                break
                
        # Fast schedule check to maintain interactive feedback
        self.root.after(100, self.poll_log_queue)

    def apply_configurations(self):
        """Retrieves entry configs and applies changes to core servers."""
        raw_pass = self.pass_entry.get().strip()
        raw_tcp = self.tcp_entry.get().strip()
        raw_udp = self.udp_entry.get().strip()
        
        if not raw_pass:
            messagebox.showerror("Validation Error", "Pairing password cannot be empty!")
            return
            
        try:
            p_tcp = int(raw_tcp)
            p_udp = int(raw_udp)
            if not (1024 <= p_tcp <= 65535) or not (1024 <= p_udp <= 65535):
                raise ValueError()
        except ValueError:
            messagebox.showerror("Validation Error", "Ports must be valid integer range 1024 to 65535.")
            return

        # Commit updates
        self.current_password = raw_pass
        self.tcp_port = p_tcp
        self.udp_port = p_udp
        
        # Deploy update parameters
        if self.server:
            self.server.auth.password = self.current_password
            
        # Port changes require socket restarts
        self.restart_backend_services()

    def toggle_streaming(self):
        """Enables/disables the screen capturing video thread on the fly."""
        self.is_streaming_enabled = self.stream_chk_var.get()
        if self.is_streaming_enabled:
            if self.streamer and not self.streamer.running:
                self.streamer.start()
                logger.info("Video screen streamer thread enabled.")
                self.stream_state_lbl.configure(text="Stream Loop Active")
        else:
            if self.streamer and self.streamer.running:
                self.streamer.stop()
                logger.info("Video screen streamer thread stopped.")
                self.stream_state_lbl.configure(text="Stream Loop Suspended")

    def toggle_input_controls(self):
        """Enables/disables the server command input simulation router."""
        self.is_controls_enabled = self.control_chk_var.get()
        if self.server:
            self.server.control.enabled = self.is_controls_enabled
        state_txt = "enabled" if self.is_controls_enabled else "disabled"
        logger.info(f"Mouse and Keyboard input redirection {state_txt}.")

    # --- Windows Autostart Registry Implementation ---
    def toggle_autostart(self):
        """Creates or destroys Windows system boot-level registry autostart entry."""
        enabled = self.auto_chk_var.get()
        success = self.set_registry_autostart(enabled)
        if success:
            action = "established" if enabled else "withdrawn"
            logger.info(f"Registry autorun path {action} successfully.")
        else:
            # revert check states
            self.auto_chk_var.set(not enabled)

    def set_registry_autostart(self, enabled: bool) -> bool:
        """Manipulates HKCU Run registry values."""
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        app_path = sys.executable
        
        # When compiling with PyInstaller, always target the bundle launcher path safely
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE)
            if enabled:
                winreg.SetValueEx(key, self.reg_key_name, 0, winreg.REG_SZ, app_path)
            else:
                try:
                    winreg.DeleteValue(key, self.reg_key_name)
                except FileNotFoundError:
                    pass
            winreg.CloseKey(key)
            return True
        except Exception as e:
            logger.error(f"Registry write issue encountered: {e}")
            messagebox.showerror("Registry Warning", f"Could not adjust Windows startup value: {e}")
            return False

    def check_autostart_enabled(self) -> bool:
        """Verifies if registry contains active program path."""
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_READ)
            value, _ = winreg.QueryValueEx(key, self.reg_key_name)
            winreg.CloseKey(key)
            return value == sys.executable
        except Exception:
            return False

    # --- System Tray Setup Code ---
    def setup_tray(self):
        """Asynchronously boots background System Tray threads."""
        import pystray
        
        # Generate Programmatic 64x64 Circle Icon to avoid needing external resources
        icon_img = Image.new("RGBA", (64, 64), color=(26, 28, 30, 0))
        draw = ImageDraw.Draw(icon_img)
        # Lavender accent circle
        draw.ellipse([6, 6, 58, 58], fill=(208, 188, 255, 255))
        # Inncer dark control core
        draw.ellipse([20, 20, 44, 44], fill=(56, 30, 114, 255))
        
        menu = (
            pystray.MenuItem("Restore Console Interface", self.restore_from_tray, default=True),
            pystray.MenuItem("Enable Streaming Feed", self.tray_toggle_stream, checked=lambda item: self.is_streaming_enabled),
            pystray.MenuItem("Enable Input Control Mode", self.tray_toggle_control, checked=lambda item: self.is_controls_enabled),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Shut Down Server", self.quit_server)
        )
        
        self.tray_icon = pystray.Icon("PCRemoteServer", icon_img, "PCRemoteServer Gateway", menu)
        # Detach/Run as a background daemon thread
        threading.Thread(target=self.tray_icon.run, daemon=True).start()

    def minimize_to_tray(self):
        """Hides the graphical application window."""
        self.root.withdraw()
        logger.info("Control Panel minimized to System Tray. Background engines continue accepting clients.")

    def restore_from_tray(self):
        """Restores window from minimized state."""
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def tray_toggle_stream(self):
        """Stream toggle wrapper for tray menu."""
        # Toggle checkbox and invoke
        self.stream_chk_var.set(not self.is_streaming_enabled)
        self.toggle_streaming()

    def tray_toggle_control(self):
        """Control toggle wrapper for tray menu."""
        self.control_chk_var.set(not self.is_controls_enabled)
        self.toggle_input_controls()

    def quit_server(self):
        """Cleans up sockets, tray icons, and quits Tkinter main loop."""
        if self.is_shutting_down:
            return
            
        self.is_shutting_down = True
        logger.info("Initializing server shutdown procedures...")
        
        # Stop servers
        if self.server:
            self.server.stop()
        if self.streamer:
            self.streamer.stop()
            
        # Tear down tray icon
        if self.tray_icon:
            self.tray_icon.stop()
            
        logger.info("Subsystems disposed. Quitting graphic display context.")
        self.root.destroy()


def main():
    # Only run Tkinter GUI loop on Windows runtime context
    root = tk.Tk()
    app = PCRemoteServerApp(root)
    root.mainloop()

if __name__ == "__main__":
    main()
