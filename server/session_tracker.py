#!/usr/bin/env python3
"""
SHRIMP Session Tracker v1.1
功能：自動追蹤 OpenClaw 的 Discord Session 並同步至暫存檔
"""
import subprocess
import json
import os
import sys

# ==========================================
# 🎯 關鍵路徑設定
# ==========================================
# 這裡我幫你預設了常見的路徑，如果執行還是報錯，
# 請在終端機輸入 which openclaw，然後把路徑貼在下面。
OPENCLAW_PATH = os.environ.get("OPENCLAW_PATH", os.path.expanduser("~/.npm-global/bin/openclaw"))
SESSION_FILE = "/tmp/shrimp_current_session"

def get_discord_session():
    """透過 OpenClaw CLI 取得最新的 Discord Direct Session"""
    try:
        # 檢查指令是否存在
        if not os.path.exists(OPENCLAW_PATH):
            print(f"❌ 錯誤：找不到 openclaw，請檢查路徑：{OPENCLAW_PATH}")
            return None

        # 執行指令
        result = subprocess.run(
            [OPENCLAW_PATH, "sessions", "--json"],
            capture_output=True,
            text=True,
            timeout=15,
            cwd=os.path.expanduser("~")
        )
        
        # 優先從 stdout 抓 JSON，沒東西再看 stderr
        output = result.stdout.strip() if result.stdout else result.stderr.strip()
        
        if not output or not output.startswith('{'):
            print(f"⚠️ 指令輸出異常：{output[:50]}")
            return None
            
        data = json.loads(output)
        sessions = data.get("sessions", [])
        
        # 篩選出 Discord 的私訊 Session
        # 邏輯：key 包含 'discord' 且 'direct'，取第一個（通常是最新的）
        for s in sessions:
            key = s.get("key", "").lower()
            if "discord" in key and "direct" in key:
                return s.get("sessionId")
                
    except json.JSONDecodeError:
        print("❌ 錯誤：解析 OpenClaw 回傳的 JSON 失敗")
    except Exception as e:
        print(f"❌ 發生非預期錯誤: {e}")
    return None

def main():
    print("🔍 正在掃描 Discord Session...")
    session_id = get_discord_session()
    
    if session_id:
        # 檢查是否跟舊的一樣
        old_id = ""
        if os.path.exists(SESSION_FILE):
            with open(SESSION_FILE, "r") as f:
                old_id = f.read().strip()
        
        if session_id != old_id:
            with open(SESSION_FILE, "w") as f:
                f.write(session_id)
            print(f"✅ 更新成功！目前 ID: {session_id}")
        else:
            print(f"📍 Session 未變動: {session_id}")
    else:
        print("⚠️ 警告：目前沒有活躍的 Discord Session，請先在 Discord 喊一下蝦蝦。")

if __name__ == "__main__":
    main()
