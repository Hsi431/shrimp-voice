# 🦐 蝦蝦語音 (Shrimp Voice)

把舊手機變成你家 AI agent 的耳朵和嘴巴。

自架的語音助理：手機 App（或瀏覽器 PWA）當衛星麥克風，喊一聲喚醒詞，
語音直接進到你的 [OpenClaw](https://openclaw.ai) agent，回覆即時串流合成語音放出來。
全程在你的區網內，雲端只碰兩個免費 API（Groq STT、edge-tts）。

```
┌─────────────┐  PCM 16kHz   ┌──────────────────────────────┐
│ Android App  │ ──── wss ──→ │ audio_ws.py                  │
│ 或瀏覽器 PWA │ ←── WAV ──── │  Porcupine 喚醒詞偵測         │
└─────────────┘              │  → Silero VAD 切句            │
                             │  → Groq Whisper STT           │
                             │  → OpenClaw agent（串流回覆） │
                             │  → edge-tts 邊講邊合成        │
                             └──────────────────────────────┘
```

## 特色

- **喚醒詞**：說「蝦蝦」（或你自己訓練的詞）才啟動，喚醒立刻回「在！」
- **串流回覆**：agent 邊生邊念，第一句話不用等整段生完；思考太久會先用「我想一下」墊場
- **省流量模式**：安靜時暫停上傳（0 KB），偵測到聲音自動補送 1.5 秒 pre-roll，喚醒後 12 秒全量保證對話完整
- **回音防護**：播放回覆時手機麥克風自動暫停，不會自己喚醒自己
- **連線 token 驗證**：區網內也不裸奔
- **感測器旁路**：手機的距離感測器和加速度計即時上傳（`/proximity`、`/accel`），可拿來做敲桌召喚之類的玩法
- **雙 client**：Android 原生 App（前景服務常駐）+ 瀏覽器 PWA

## 你需要自己申請的東西（都免費）

| 項目 | 哪裡申請 | 用途 |
|---|---|---|
| Groq API key | [console.groq.com](https://console.groq.com) | Whisper 語音辨識 |
| Picovoice AccessKey | [console.picovoice.ai](https://console.picovoice.ai) | 喚醒詞引擎 |
| 喚醒詞 `.ppn` 檔 | 同上，Console 裡訓練 | ⚠️ **`.ppn` 綁定你的 AccessKey，不能用別人的**，所以本 repo 不附。訓練時平台選 Linux、語言選中文 |
| 中文聲學模型 `.pv` | [Porcupine repo](https://github.com/Picovoice/porcupine/tree/master/lib/common) 下載 `porcupine_params_zh.pv` | 中文喚醒詞必備（英文喚醒詞不用） |

edge-tts（微軟 TTS）不用申請。

## 伺服器安裝

需求：Linux、Python 3.10+、ffmpeg、Node.js、已設定好的 OpenClaw（gateway 跑在本機）。

```bash
git clone https://github.com/<you>/shrimp-voice.git
cd shrimp-voice/server
pip install -r requirements.txt

# 自簽 TLS 憑證（瀏覽器要 https 才給麥克風權限）
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 3650 -nodes -subj "/CN=shrimp-voice"

# 放入你訓練的喚醒詞和中文模型
cp ~/Downloads/你的喚醒詞_zh_linux_*.ppn wake_word.ppn
cp ~/Downloads/porcupine_params_zh.pv .

# 設定
cp .env.example .env
nano .env   # 填 GROQ_API_KEY、PV_ACCESS_KEY，建議也設 SHRIMP_WS_TOKEN

python3 audio_ws.py
```

看到 `喚醒音效 [在！] 已就緒` 就是活了。瀏覽器開 `https://<server-ip>:8443/`（自簽憑證要手動信任一次；有設 token 的話第一次帶 `?token=你的token`）。

長期跑用 systemd：`examples/` 裡有現成的 user unit，含每分鐘自動同步 OpenClaw session 的 timer。

> ⚠️ 有開防火牆（ufw 等）記得放行：`sudo ufw allow from 192.168.0.0/16 to any port 8443 proto tcp`

## Android App

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

裝好後在 App 裡填伺服器位址（如 `192.168.1.100:8443`）和 token，按「啟動服務並開啟麥克風」。
建議把 App 加入電池最佳化例外，三星手機尤其需要。

省流量模式預設關閉（全時串流喚醒率最高）；開了之後安靜時完全不上傳，
RMS 門檻等參數在 `PcmAudioStreamer` 的 `RmsUploadGate`，預設值是按一般室內環境調的。

## 大腦：目前綁 OpenClaw

老實說：現在的「大腦」層寫死接 OpenClaw gateway（`openclaw_stream_agent.mjs`）。
它的介面其實很簡單——吃 argv 訊息、stdout 逐行吐
`{"type":"delta","text":...}` / `{"type":"final",...}` / `{"type":"error",...}`，
理論上任何能串流輸出的 agent CLI 都能照這個格式寫一個橋接檔換上去（改 `.env` 不用動主程式）。
抽象化是 roadmap 上的事，歡迎 PR。

## 設定一覽

所有設定在 `server/.env`，完整列表和說明見 [`.env.example`](server/.env.example)。
常用的：`WAKE_SENSITIVITY`（誤喚醒就調低）、`TTS_VOICE`（換聲音）、`WAKE_REPLY`（換應答詞）。

## 安全注意

- 這是設計給**自家區網**用的。token 擋得住鄰居，擋不住有心人——不要直接暴露到公網
- client 端信任自簽憑證（不驗 CA），符合區網場景；要更嚴謹可自行做 certificate pinning
- 語音指令直通你的 agent，agent 能做什麼，喊話的人就能做什麼——token 請當密碼看待

## License

MIT（不含 Porcupine 模型檔與你自己訓練的喚醒詞，那些依 Picovoice 條款）
