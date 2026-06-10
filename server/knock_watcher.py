#!/usr/bin/env python3
"""
敲桌召喚 watcher（S21 Ticket B）

輪詢 voice server 的 /accel_raw（手機加速度計 ring buffer），
偵測「篤篤」雙敲擊 → POST /wake 觸發與喚醒詞相同的下游。

用 accel 而非工單原訂的 RMS：講話/放杯子不會動到加速度計，
天生解掉 B1 的誤觸發疑慮。

校準（2026-06-10，S23/S21 平放桌面、FASTEST 取樣）：
- 重力基線 ~9.8 m/s²，靜置起伏 < ±0.3
- 敲桌峰值 12~13（孤立 1~2 樣本脈衝）
- 拿起手機 = 連續多樣本大偏移（非孤立脈衝，靠鄰居檢查排除）
"""
import os, time, statistics
import requests
import urllib3

urllib3.disable_warnings()

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER = "https://127.0.0.1:8443"

POLL_S = 0.3
PEAK_DELTA = 2.0        # 峰值需高於基線多少 (m/s²)
NEIGHBOR_DELTA = 1.0    # 孤立脈衝：峰值 ±2 樣本需回到基線附近
GAP_MIN_SAMPLES = 3     # 雙敲間隔下限（~30Hz 上傳率 → 100ms）
GAP_MAX_SAMPLES = 25    # 上限（~830ms）
COOLDOWN_S = 12         # ring buffer 留 10 秒，冷卻蓋過它避免同批波峰重複觸發


def load_token():
    try:
        with open(os.path.join(BASE_DIR, ".env")) as f:
            for line in f:
                line = line.strip()
                if line.startswith("SHRIMP_WS_TOKEN="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return ""


TOKEN = load_token()


def find_double_knock(v):
    """回傳 (i, j, peak_i, peak_j, base) 或 None。"""
    if len(v) < 10:
        return None
    base = statistics.median(v)
    peaks = []
    for i in range(2, len(v) - 2):
        if v[i] > base + PEAK_DELTA and v[i] >= v[i - 1] and v[i] >= v[i + 1]:
            if v[i - 2] < base + NEIGHBOR_DELTA and v[i + 2] < base + NEIGHBOR_DELTA:
                peaks.append(i)
    for a, b in zip(peaks, peaks[1:]):
        if GAP_MIN_SAMPLES <= b - a <= GAP_MAX_SAMPLES:
            return (a, b, round(v[a], 1), round(v[b], 1), round(base, 2))
    return None


def main():
    print(f"knock-watcher: 啟動 (token={'有' if TOKEN else '無'})", flush=True)
    last_trigger = 0.0
    while True:
        try:
            r = requests.get(f"{SERVER}/accel_raw", verify=False, timeout=5)
            values = r.json().get("values", [])
            hit = find_double_knock(values)
            if hit and time.time() - last_trigger > COOLDOWN_S:
                last_trigger = time.time()
                print(f"knock-watcher: 雙敲擊! pos=({hit[0]},{hit[1]}) "
                      f"peaks=({hit[2]},{hit[3]}) base={hit[4]} → /wake", flush=True)
                requests.post(f"{SERVER}/wake", params={"token": TOKEN},
                              verify=False, timeout=5)
        except Exception as e:
            print(f"knock-watcher: {type(e).__name__}: {e}", flush=True)
            time.sleep(3)
        time.sleep(POLL_S)


if __name__ == "__main__":
    main()
