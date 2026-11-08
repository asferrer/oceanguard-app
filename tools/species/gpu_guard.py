"""Start the lab GPU guard for the current process.

Every GPU job in these tools calls :func:`start` first. The guard
(``rerun_limpio/run/gpu_guard/gpu_monitor.py``) samples ``nvidia-smi`` and kills
this process (``--kill-pid``) on >= 83 degC, on power over the limit or when the
GPU stops answering. The path can be overridden with ``BIODEX_GPU_GUARD``.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

DEFAULT_GUARD = Path(r"C:\Users\aleja\Desktop\Doctorado\rerun_limpio\run\gpu_guard\gpu_monitor.py")


def check_gpu_free() -> None:
    """Abort if nvidia-smi fails or another process is using a lot of VRAM."""
    out = subprocess.run(["nvidia-smi", "--query-gpu=memory.used,utilization.gpu",
                          "--format=csv,noheader,nounits"], capture_output=True, text=True)
    text = (out.stdout + out.stderr).strip()
    if out.returncode != 0 or "lost" in text.lower():
        sys.exit(f"nvidia-smi failed: {text}")
    used, util = (float(v) for v in text.splitlines()[0].split(","))
    if used > 12000 or util > 50:
        sys.exit(f"GPU busy (memory.used={used} MiB, util={util}%): another job is running")


def start(log_dir: Path, max_power: float = 420.0) -> subprocess.Popen | None:
    guard = Path(os.environ.get("BIODEX_GPU_GUARD", DEFAULT_GUARD))
    if not guard.exists():
        sys.exit(f"GPU guard not found at {guard}")
    check_gpu_free()
    log_dir.mkdir(parents=True, exist_ok=True)
    return subprocess.Popen([sys.executable, str(guard), "--log-dir", str(log_dir),
                             "--interval", "5", "--max-temp", "83", "--max-power", str(max_power),
                             "--kill-pid", str(os.getpid())])
