#!/usr/bin/env python3
"""
蝦蝦語音 (Shrimp Voice) — 自架語音助理伺服器

手機 App / PWA 把麥克風 PCM 串流到這裡，流程：
Porcupine 喚醒詞 → Silero VAD 切句 → Groq Whisper STT
→ OpenClaw agent（串流回覆）→ edge-tts 邊講邊合成送回 client。
所有設定走同目錄的 .env，見 README。
"""
import asyncio, os, ssl, requests, subprocess, json, re, wave, time, random
import sys, uuid, collections
import numpy as np
import torch
import pvporcupine
from aiohttp import web, WSMsgType
from silero_vad import load_silero_vad, get_speech_timestamps
import edge_tts

# ==========================================
# 🔑 老闆的環境設定區
# ==========================================
def load_env_file(path):
    env = {}
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    except OSError:
        pass
    return env

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
ENV = load_env_file(os.path.join(BASE_DIR, ".env"))

GROQ_API_KEY = ENV.get("GROQ_API_KEY", "")
if not GROQ_API_KEY:
    print("Error: .env 缺 GROQ_API_KEY（STT 需要，https://console.groq.com 免費申請）", flush=True)
    sys.exit(1)

PICOVOICE_API_KEY = ENV.get("PV_ACCESS_KEY", "")
if not PICOVOICE_API_KEY:
    print("Error: .env 缺 PV_ACCESS_KEY（https://console.picovoice.ai 免費申請）", flush=True)
    sys.exit(1)

# WebSocket 簡易驗證：.env 設 SHRIMP_WS_TOKEN 後才啟用（client 帶 ?token=）
WS_TOKEN = ENV.get("SHRIMP_WS_TOKEN", "")

# 喚醒詞靈敏度 0~1：越高越靈敏但誤喚醒越多（Porcupine 預設 0.5）
WAKE_SENSITIVITY = float(ENV.get("WAKE_SENSITIVITY", "0.6"))

# 喚醒詞 .ppn 要在 Picovoice Console 用自己的帳號訓練（檔案綁定 AccessKey，不能共用）
KEYWORD_PATH = ENV.get("KEYWORD_PATH", os.path.join(BASE_DIR, "wake_word.ppn"))
# 中文聲學模型：Porcupine GitHub repo 的 lib/common/porcupine_params_zh.pv
MODEL_PATH = ENV.get("PORCUPINE_MODEL_PATH", os.path.join(BASE_DIR, "porcupine_params_zh.pv"))
for _p, _what in ((KEYWORD_PATH, "喚醒詞 .ppn"), (MODEL_PATH, "Porcupine 中文模型 .pv")):
    if not os.path.exists(_p):
        print(f"Error: 找不到{_what}: {_p}（見 README 安裝說明）", flush=True)
        sys.exit(1)

OPENCLAW_PATH = ENV.get("OPENCLAW_PATH", os.path.expanduser("~/.npm-global/bin/openclaw"))
STREAM_AGENT_PATH = os.path.join(BASE_DIR, "openclaw_stream_agent.mjs")
SESSION_ID_FILE = ENV.get("SESSION_ID_FILE", "/tmp/shrimp_current_session")
DEFAULT_SESSION_ID = ENV.get("DEFAULT_SESSION_ID", "")
AGENT_ID = ENV.get("AGENT_ID", "main")
NODE_BIN = ENV.get("NODE_BIN", "/usr/bin/node")
TTS_VOICE = ENV.get("TTS_VOICE", "zh-TW-HsiaoChenNeural")
WAKE_REPLY = ENV.get("WAKE_REPLY", "在！")
PORT = int(ENV.get("PORT", "8443"))
SSL_CERT = ENV.get("SSL_CERT", os.path.join(BASE_DIR, "cert.pem"))
SSL_KEY = ENV.get("SSL_KEY", os.path.join(BASE_DIR, "key.pem"))

SAMPLERATE = 16000
WAKE_AUDIO_BYTES = b""
SILENCE_CHUNKS_TO_END = 3
ACTIVE_WEBSOCKETS = set()
BACKGROUND_TASKS = set()
VOICE_RESPONSE_INSTRUCTION = (
    "你正在用語音和使用者即時對話。請用繁體中文，回答要短、直接、口語化；"
    "除非使用者要求詳細說明，否則控制在 1 到 2 句、60 字以內。"
    "不要使用 Markdown、emoji、條列或裝飾符號。"
)
THINKING_FILLERS = [
    "我想一下。",
    "等我一下。",
    "我看看。",
    "嗯，我想一下。",
    "讓我想想。",
    "我整理一下。",
    "稍等我一下。",
    "我看一下怎麼說。",
    "嗯，等一下。",
    "我想想看。",
    "讓我確認一下。",
    "我先想一下。"
]

# ============ RMS 旁路計算 ============
RMS_BUFFER = []  # 每秒累積的 RMS 值
RMS_RAW_BUFFER = collections.deque(maxlen=300)  # 逐幀 RMS ring buffer（~10秒, 給雙擊偵測用）
CURRENT_RMS = 0.0
CURRENT_DB = -100.0
LAST_RMS_LOG_TIME = 0.0

# ============ 感測器 (proximity + accelerometer) ============
SENSOR_PROXIMITY = float('inf')        # latest proximity value
SENSOR_PROXIMITY_NEAR = False          # near flag
SENSOR_ACCEL = {"x": 0.0, "y": 0.0, "z": 9.8, "mag": 9.8}
SENSOR_ACCEL_RAW = collections.deque(maxlen=300)  # accel magnitude ring buffer

def compute_rms(audio_i16):
    """從 int16 PCM frame 計算 RMS 與 dB。"""
    if len(audio_i16) == 0:
        return 0.0, -100.0
    audio_f32 = audio_i16.astype(np.float32)
    rms = np.sqrt(np.mean(audio_f32 ** 2))
    db = 20 * np.log10(rms + 1e-10)
    return rms, db

def process_rms_frame(audio_i16):
    """對一個 frame 做 RMS 計算，每秒輸出平均。
    純旁路：不影響 original flow。
    """
    global RMS_BUFFER, RMS_RAW_BUFFER, CURRENT_RMS, CURRENT_DB, LAST_RMS_LOG_TIME
    rms_val, db_val = compute_rms(audio_i16)
    RMS_BUFFER.append(rms_val)
    RMS_RAW_BUFFER.append(rms_val)  # 給雙擊偵測用
    now = time.monotonic()
    if now - LAST_RMS_LOG_TIME >= 1.0 and RMS_BUFFER:
        avg_rms = float(np.mean(RMS_BUFFER))
        avg_db = 20 * np.log10(avg_rms + 1e-10)
        CURRENT_RMS = avg_rms
        CURRENT_DB = avg_db
        audio_log("rms", rms_avg=avg_rms, db_avg=avg_db)
        RMS_BUFFER = []
        LAST_RMS_LOG_TIME = now

print("System: 正在啟動 SHRIMP CORE v2.8...", flush=True)
vad_model = load_silero_vad()

def now_ms():
    return int(time.monotonic() * 1000)

def latency_log(stage, start_ms=None, **fields):
    parts = [f"stage={stage}"]
    if start_ms is not None:
        parts.append(f"elapsedMs={now_ms() - start_ms}")
    parts.extend(f"{k}={v}" for k, v in fields.items())
    print("[voice-latency] " + " ".join(parts), flush=True)

def audio_log(stage, **fields):
    parts = [f"stage={stage}"]
    parts.extend(f"{k}={v}" for k, v in fields.items())
    print("[voice-audio] " + " ".join(parts), flush=True)

def audio_ms_from_bytes(byte_count):
    return int(byte_count / 2 / SAMPLERATE * 1000)

async def rms_handler(request):
    """取得當前 RMS 數值。"""
    return web.json_response({
        "rms": round(CURRENT_RMS, 6),
        "db": round(CURRENT_DB, 2),
        "level": (
            "quiet" if CURRENT_DB < -40 else
            "normal" if CURRENT_DB < -20 else
            "loud"
        )
    })

async def rms_raw_handler(request):
    """取得最近逐幀 RMS 數值（給雙擊偵測用）。"""
    return web.json_response({
        "count": len(RMS_RAW_BUFFER),
        "values": list(RMS_RAW_BUFFER)
    })

async def proximity_handler(request):
    """取得目前 Palm Proximity 感測器數值。"""
    return web.json_response({
        "near": SENSOR_PROXIMITY_NEAR,
        "value": SENSOR_PROXIMITY
    })

async def accel_handler(request):
    """取得目前 Accelerometer 數值。"""
    return web.json_response(SENSOR_ACCEL)

async def accel_raw_handler(request):
    """取得最近 Accelerometer magnitude ring buffer。"""
    return web.json_response({
        "count": len(SENSOR_ACCEL_RAW),
        "values": list(SENSOR_ACCEL_RAW)
    })

async def on_startup(app):
    """預載喚醒音效 [在！]"""
    global WAKE_AUDIO_BYTES
    try:
        c = edge_tts.Communicate(WAKE_REPLY, TTS_VOICE, rate="+40%")
        await c.save("/tmp/shrimp_wake.mp3")
        os.system(f"ffmpeg -y -i /tmp/shrimp_wake.mp3 -ar 16000 -ac 1 /tmp/shrimp_wake.wav -loglevel quiet")
        with open("/tmp/shrimp_wake.wav", "rb") as f: WAKE_AUDIO_BYTES = f.read()
        print("System: 喚醒音效 [在！] 已就緒", flush=True)
    except: print("Warning: 預載音效失敗")

async def on_shutdown(app):
    print("System: 正在關閉語音服務...", flush=True)
    for ws in list(ACTIVE_WEBSOCKETS):
        try:
            await ws.close(code=1001, message=b"server shutdown")
        except Exception:
            pass

    for task in list(BACKGROUND_TASKS):
        task.cancel()
    if BACKGROUND_TASKS:
        await asyncio.gather(*list(BACKGROUND_TASKS), return_exceptions=True)

async def on_cleanup(app):
    ACTIVE_WEBSOCKETS.clear()
    BACKGROUND_TASKS.clear()
    print("System: 語音服務已關閉", flush=True)

def track_task(task):
    BACKGROUND_TASKS.add(task)
    task.add_done_callback(lambda done: BACKGROUND_TASKS.discard(done))
    return task

def get_current_session_id():
    """讀取最新 Session ID"""
    try:
        if os.path.exists(SESSION_ID_FILE):
            with open(SESSION_ID_FILE, "r") as f:
                sid = f.read().strip()
                if sid: return sid
    except: pass
    return DEFAULT_SESSION_ID

def clean_for_tts(text):
    if not text: return ""
    text = re.sub(r'\*\*|__|\*|_|#', '', text)
    return re.sub(r'[^\u4e00-\u9fa50-9\u3002\uff0c\uff1f\uff01\u0021-\u007e\u3001\uff1a\uff1b]', '', text).replace("\n", " ").strip()

async def send_ui_log(ws, tag, msg, event=None):
    try:
        if not ws.closed:
            payload = {"tag": tag, "msg": msg}
            if event:
                payload["event"] = event
            await ws.send_str(json.dumps(payload))
    except: pass

async def audio_handler(request):
    if WS_TOKEN and request.query.get("token", "") != WS_TOKEN:
        audio_log("ws_auth_rejected", remote=request.remote)
        return web.Response(status=401, text="unauthorized")
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    ACTIVE_WEBSOCKETS.add(ws)
    conn_ms = now_ms()
    
    try:
        porcupine = pvporcupine.create(
            access_key=PICOVOICE_API_KEY,
            keyword_paths=[KEYWORD_PATH],
            model_path=MODEL_PATH,
            sensitivities=[WAKE_SENSITIVITY]
        )
    except Exception as e:
        print(f"Error: Porcupine 載入失敗: {e}"); await ws.close(); return ws

    is_awake, p_buf, s_buf, pre_buf, silence, wake_ms = False, [], [], [], 0, None
    last_bin_ms = None
    turn_state = {"processing": False, "cooldown_until": 0}
    total_chunks, total_bytes = 0, 0
    pre_wake_chunks, pre_wake_bytes = 0, 0
    awake_chunks, speech_chunks, vad_silence_chunks = 0, 0, 0
    last_no_wake_log_ms = conn_ms
    MAX_PRE = int(SAMPLERATE * 0.5)
    
    try:
        async for msg in ws:
            # 🦐 感測器資料 (proximity / accel) — 文字訊息
            if msg.type == WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                    if data.get("type") == "sensor":
                        global SENSOR_PROXIMITY, SENSOR_PROXIMITY_NEAR, SENSOR_ACCEL, SENSOR_ACCEL_RAW
                        if "prox" in data:
                            SENSOR_PROXIMITY = data["prox"]
                            SENSOR_PROXIMITY_NEAR = data.get("proxNear", False)
                        if "aMag" in data:
                            mag = data["aMag"]
                            SENSOR_ACCEL = {
                                "x": data.get("ax", 0.0),
                                "y": data.get("ay", 0.0),
                                "z": data.get("az", 9.8),
                                "mag": mag
                            }
                            SENSOR_ACCEL_RAW.append(mag)
                except Exception:
                    pass
                continue

            if msg.type == WSMsgType.BINARY:
                bin_ms = now_ms()
                # 省流量 gate 會造成串流斷層；跳接會污染 Porcupine 內部狀態，
                # 害喚醒詞分數被拉低。斷層後墊靜音把狀態洗回乾淨基線。
                if not is_awake and last_bin_ms is not None and bin_ms - last_bin_ms > 1000:
                    p_buf = [0] * int(SAMPLERATE * 0.8)
                    audio_log("stream_gap_padded", gapMs=bin_ms - last_bin_ms)
                last_bin_ms = bin_ms
                total_chunks += 1
                total_bytes += len(msg.data)
                data_i16 = np.frombuffer(msg.data, dtype=np.int16)

                # 🦐 RMS 旁路計算（不影響任何原有流程）
                try:
                    process_rms_frame(data_i16)
                except Exception:
                    pass  # 絕不因 RMS 影響主流程

                if turn_state["processing"]:
                    continue
                if now_ms() < turn_state["cooldown_until"]:
                    p_buf = []
                    continue

                if not is_awake:
                    pre_wake_chunks += 1
                    pre_wake_bytes += len(msg.data)
                    if now_ms() - last_no_wake_log_ms >= 15000:
                        audio_log(
                            "pre_wake_no_detection",
                            connElapsedMs=now_ms() - conn_ms,
                            chunks=pre_wake_chunks,
                            audioMs=audio_ms_from_bytes(pre_wake_bytes)
                        )
                        last_no_wake_log_ms = now_ms()
                    p_buf.extend(data_i16.tolist())
                    while len(p_buf) >= porcupine.frame_length:
                        frame = p_buf[:porcupine.frame_length]
                        p_buf = p_buf[porcupine.frame_length:]
                        if porcupine.process(frame) >= 0:
                            wake_ms = now_ms()
                            latency_log("wake_detected")
                            audio_log(
                                "wake_detected",
                                preWakeChunks=pre_wake_chunks,
                                preWakeAudioMs=audio_ms_from_bytes(pre_wake_bytes),
                                connElapsedMs=wake_ms - conn_ms
                            )
                            is_awake = True
                            if WAKE_AUDIO_BYTES: await ws.send_bytes(WAKE_AUDIO_BYTES)
                            await send_ui_log(ws, "SYS", "已喚醒！老闆請講...", "wake_detected")
                            s_buf, silence, pre_buf = [], 0, []
                            awake_chunks, speech_chunks, vad_silence_chunks = 0, 0, 0
                            break
                else:
                    awake_chunks += 1
                    data_f32 = data_i16.astype(np.float32) / 32767.0
                    if get_speech_timestamps(torch.tensor(data_f32), vad_model, threshold=0.25, sampling_rate=SAMPLERATE):
                        speech_chunks += 1
                        if not s_buf: s_buf.extend(pre_buf)
                        s_buf.extend(data_f32.tolist()); silence = 0
                    else:
                        silence += 1
                        vad_silence_chunks += 1
                        if s_buf: s_buf.extend(data_f32.tolist())
                        else:
                            pre_buf.extend(data_f32.tolist())
                            if len(pre_buf) > MAX_PRE: pre_buf = pre_buf[-MAX_PRE:]

                    if not s_buf and wake_ms and now_ms() - wake_ms >= 5000:
                        audio_log(
                            "wake_timeout_no_speech",
                            awakeChunks=awake_chunks,
                            vadSilenceChunks=vad_silence_chunks
                        )
                        is_awake, s_buf, pre_buf, silence = False, [], [], 0
                        pre_wake_chunks, pre_wake_bytes = 0, 0
                        last_no_wake_log_ms = now_ms()
                        continue
                    
                    if silence >= SILENCE_CHUNKS_TO_END and len(s_buf) >= SAMPLERATE:
                        latency_log(
                            "vad_end",
                            wake_ms,
                            audioMs=int(len(s_buf) / SAMPLERATE * 1000),
                            silenceChunks=silence
                        )
                        audio_log(
                            "vad_end",
                            awakeChunks=awake_chunks,
                            speechChunks=speech_chunks,
                            vadSilenceChunks=vad_silence_chunks,
                            speechAudioMs=int(len(s_buf) / SAMPLERATE * 1000)
                        )
                        to_proc, is_awake, s_buf, pre_buf, silence = list(s_buf), False, [], [], 0
                        pre_wake_chunks, pre_wake_bytes = 0, 0
                        last_no_wake_log_ms = now_ms()
                        turn_state["processing"] = True
                        task = track_task(asyncio.create_task(run_shrimp_logic(ws, to_proc, wake_ms)))
                        task.add_done_callback(lambda _done: turn_state.update(
                            processing=False,
                            cooldown_until=now_ms() + 3000
                        ))
                        task.add_done_callback(lambda _done: audio_log("turn_task_done"))
    finally:
        audio_log(
            "connection_closed",
            totalChunks=total_chunks,
            totalAudioMs=audio_ms_from_bytes(total_bytes),
            connElapsedMs=now_ms() - conn_ms
        )
        ACTIVE_WEBSOCKETS.discard(ws)
        porcupine.delete(); await ws.close()
    return ws

async def run_shrimp_logic(ws, audio_data, wake_ms=None):
    turn_ms = now_ms()
    await send_ui_log(ws, "SYS", "正在解析電信號...", "stt_start")
    latency_log("stt_start", wake_ms, audioMs=int(len(audio_data) / SAMPLERATE * 1000))
    text = await asyncio.to_thread(stt_engine, audio_data)
    latency_log("stt_end", turn_ms, textLen=len(text or ""))
    if not text or len(text.strip()) < 3:
        await send_ui_log(ws, "ERR", "沒有辨識到有效語音")
        latency_log("turn_abort_empty_stt", wake_ms)
        return

    await send_ui_log(ws, "STT", text, "stt_end")
    await send_ui_log(ws, "SYS", "思考模組啟動...", "agent_start")

    agent_ms = now_ms()
    latency_log("agent_start", wake_ms, textLen=len(text))
    response = await stream_shrimp_brain_to_tts(ws, text, wake_ms)
    if response is None:
        latency_log("agent_stream_fallback", wake_ms)
        response = await asyncio.to_thread(shrimp_brain, text)
        latency_log("agent_end", agent_ms, responseLen=len(response or ""))
        if response:
            clean_text = clean_for_tts(response)
            await synthesize_and_send_tts(ws, clean_text, wake_ms)
            latency_log("turn_done", wake_ms)
            await send_ui_log(ws, "AI", response, "agent_end")
            await send_ui_log(ws, "SYS", "回傳完畢，進入待命。", "idle")
        return

    latency_log("agent_stream_end", agent_ms, responseLen=len(response or ""))
    if not response:
        await send_ui_log(ws, "ERR", "大腦無響應")
        latency_log("turn_abort_empty_agent", wake_ms)
        return

    await send_ui_log(ws, "AI", response, "agent_end")
    latency_log("turn_done", wake_ms)
    await send_ui_log(ws, "SYS", "回傳完畢，進入待命。", "idle")

async def stream_shrimp_brain_to_tts(ws, text, wake_ms=None):
    sid = get_current_session_id()
    voice_text = make_voice_prompt(text)
    tts_queue = asyncio.Queue()
    tts_task = asyncio.create_task(tts_queue_consumer(ws, tts_queue, wake_ms))
    full_response, delta_seen, first_delta_logged = "", False, False
    buffer = ""
    first_delta_event = asyncio.Event()
    filler_task = asyncio.create_task(thinking_filler_if_slow(ws, tts_queue, first_delta_event, wake_ms))

    try:
        proc = await asyncio.create_subprocess_exec(
            NODE_BIN, STREAM_AGENT_PATH, voice_text, sid, AGENT_ID,
            cwd=os.path.expanduser("~"),
            env={**os.environ, "NODE_NO_WARNINGS": "1"},
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        stderr_task = asyncio.create_task(drain_stream_stderr(proc.stderr))

        while True:
            line = await proc.stdout.readline()
            if not line:
                break
            try:
                event = json.loads(line.decode("utf-8"))
            except Exception:
                continue

            event_type = event.get("type")
            if event_type == "delta":
                delta = event.get("text") or ""
                if not delta:
                    continue
                delta_seen = True
                full_response = event.get("fullText") or (full_response + delta)
                if not first_delta_logged:
                    latency_log("agent_first_delta", wake_ms)
                    first_delta_logged = True
                    first_delta_event.set()
                    await send_ui_log(ws, "SYS", "開始回覆...", "agent_first_delta")
                buffer += delta
                buffer = await flush_speakable_chunks(tts_queue, buffer)
            elif event_type == "final":
                full_response = event.get("text") or full_response
            elif event_type == "error":
                print(f"[voice-stream] helper error: {event.get('message')}", flush=True)
                if not delta_seen:
                    await proc.wait()
                    await stderr_task
                    filler_task.cancel()
                    await asyncio.gather(filler_task, return_exceptions=True)
                    await tts_queue.put(None)
                    await tts_task
                    return None

        rc = await proc.wait()
        await stderr_task
        first_delta_event.set()
        if rc != 0 and not delta_seen:
            filler_task.cancel()
            await asyncio.gather(filler_task, return_exceptions=True)
            await tts_queue.put(None)
            await tts_task
            return None

        final_tail = clean_for_tts(buffer)
        if final_tail:
            await tts_queue.put(final_tail)
        elif not delta_seen and full_response:
            final_text = clean_for_tts(full_response)
            if final_text:
                await tts_queue.put(final_text)

        await tts_queue.put(None)
        await tts_task
        await filler_task
        return full_response
    except Exception as e:
        first_delta_event.set()
        latency_log("agent_stream_error", wake_ms, err=type(e).__name__)
        await tts_queue.put(None)
        await tts_task
        await filler_task
        return None

async def thinking_filler_if_slow(ws, tts_queue, first_delta_event, wake_ms=None):
    try:
        await asyncio.wait_for(first_delta_event.wait(), timeout=2.5)
    except asyncio.TimeoutError:
        filler = random.choice(THINKING_FILLERS)
        latency_log("thinking_filler_start", wake_ms, textLen=len(filler))
        await send_ui_log(ws, "SYS", "思考中...", "thinking_filler")
        await tts_queue.put(filler)

async def flush_speakable_chunks(tts_queue, buffer):
    while True:
        chunk, rest = split_speakable_chunk(buffer)
        if not chunk:
            return buffer
        clean_chunk = clean_for_tts(chunk)
        if clean_chunk:
            await tts_queue.put(clean_chunk)
        buffer = rest

def split_speakable_chunk(buffer):
    text = buffer.strip()
    if not text:
        return None, buffer

    punct = re.search(r'[。！？!?；;]\s*', buffer)
    if punct and len(clean_for_tts(buffer[:punct.end()])) >= 4:
        return buffer[:punct.end()], buffer[punct.end():]

    soft_punct = re.search(r'[，,、]\s*', buffer)
    if soft_punct and len(clean_for_tts(buffer[:soft_punct.end()])) >= 10:
        return buffer[:soft_punct.end()], buffer[soft_punct.end():]

    if len(clean_for_tts(buffer)) >= 22:
        return buffer, ""

    return None, buffer

async def tts_queue_consumer(ws, queue, wake_ms=None):
    chunk_index = 0
    announced = False
    while True:
        clean_text = await queue.get()
        if clean_text is None:
            return
        if not announced:
            announced = True
            await send_ui_log(ws, "SYS", "語音合成中...", "tts_start")
        chunk_index += 1
        await synthesize_and_send_tts(ws, clean_text, wake_ms, chunk_index)

async def synthesize_and_send_tts(ws, clean_text, wake_ms=None, chunk_index=1):
    if not clean_text or (chunk_index > 1 and len(clean_text.strip()) < 3):
        audio_log("tts_chunk_skipped", chunk=chunk_index, cleanTextLen=len(clean_text or ""))
        return
    tts_ms = now_ms()
    latency_log("tts_chunk_start", wake_ms, chunk=chunk_index, cleanTextLen=len(clean_text))
    uid = uuid.uuid4().hex
    mp3_path = f"/tmp/voice_out_{uid}.mp3"
    wav_path = f"/tmp/voice_out_{uid}.wav"
    try:
        tts = edge_tts.Communicate(clean_text, TTS_VOICE)
        await tts.save(mp3_path)
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-y", "-i", mp3_path, "-ar", "16000", "-ac", "1", wav_path, "-loglevel", "quiet"
        )
        await proc.wait()
        if proc.returncode != 0:
            raise RuntimeError(f"ffmpeg failed: {proc.returncode}")
        with open(wav_path, "rb") as f:
            if not ws.closed:
                await ws.send_bytes(f.read())
        latency_log("tts_chunk_sent", tts_ms, chunk=chunk_index)
    except Exception as e:
        latency_log("tts_error", wake_ms, err=type(e).__name__, chunk=chunk_index)
        await send_ui_log(ws, "ERR", "合成失敗")
    finally:
        for path in (mp3_path, wav_path):
            try:
                os.remove(path)
            except OSError:
                pass

async def drain_stream_stderr(stream):
    if stream is None:
        return
    while True:
        line = await stream.readline()
        if not line:
            return
        text = line.decode("utf-8", errors="replace").strip()
        if text and "DeprecationWarning" not in text:
            print(f"[voice-stream] {text}", flush=True)

def stt_engine(audio_data):
    wav_path = f"/tmp/voice_in_{uuid.uuid4().hex}.wav"
    try:
        with wave.open(wav_path, 'wb') as f:
            f.setnchannels(1); f.setsampwidth(2); f.setframerate(SAMPLERATE); f.writeframes((np.array(audio_data)*32767).astype(np.int16).tobytes())
        with open(wav_path, "rb") as audio_file:
            r = requests.post("https://api.groq.com/openai/v1/audio/transcriptions",
                              headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
                              files={"file": ("a.wav", audio_file, "audio/wav")},
                              data={"model": "whisper-large-v3", "language": "zh"}, timeout=20)
        return r.json().get("text", "").strip() if r.status_code == 200 else None
    finally:
        try:
            os.remove(wav_path)
        except OSError:
            pass

def shrimp_brain(text):
    sid = get_current_session_id()
    # 🎯 使用修正後的絕對路徑執行 OpenClaw
    voice_text = make_voice_prompt(text)
    cmd = [OPENCLAW_PATH, "agent", "--message", voice_text, "--json"]
    if sid:
        cmd += ["--session-id", sid]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=90, cwd=os.path.expanduser("~"))
        raw = res.stdout.strip()
        data = json.loads(raw[raw.find("{"):])
        p = data.get("payloads") or data.get("result", {}).get("payloads", [])
        return p[0].get("text", "").strip() if p else data.get("text")
    except: return None

def make_voice_prompt(text):
    return f"{VOICE_RESPONSE_INSTRUCTION}\n\n使用者：{text}"

app = web.Application(); app.on_startup.append(on_startup); app.on_shutdown.append(on_shutdown); app.on_cleanup.append(on_cleanup)
app.router.add_get("/", lambda r: web.FileResponse(os.path.join(BASE_DIR, "templates", "index.html")))
app.router.add_get("/audio", audio_handler)
app.router.add_get("/rms", rms_handler)
app.router.add_get("/rms_raw", rms_raw_handler)
app.router.add_get("/proximity", proximity_handler)
app.router.add_get("/accel", accel_handler)
app.router.add_get("/accel_raw", accel_raw_handler)
# Static files for PWA
app.router.add_static("/static", os.path.join(BASE_DIR, "static"))
app.router.add_get("/manifest.json", lambda r: web.json_response({
    "name": "🦐 蝦蝦語音",
    "short_name": "蝦蝦語音",
    "description": "SHRIMP OS 語音對話系統",
    "start_url": "/",
    "display": "standalone",
    "background_color": "#1a1a2e",
    "theme_color": "#1a1a2e",
    "orientation": "portrait",
    "icons": [
        {"src": "/static/icon-192.png", "sizes": "192x192", "type": "image/png"},
        {"src": "/static/icon-512.png", "sizes": "512x512", "type": "image/png"}
    ]
}))
ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
try:
    ssl_ctx.load_cert_chain(SSL_CERT, SSL_KEY)
except FileNotFoundError:
    print(f"Error: 找不到 TLS 憑證 {SSL_CERT} / {SSL_KEY}，先照 README 用 openssl 產生自簽憑證", flush=True)
    sys.exit(1)
web.run_app(app, host="0.0.0.0", port=PORT, ssl_context=ssl_ctx)
