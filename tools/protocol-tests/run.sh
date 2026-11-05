#!/usr/bin/env bash
# 協定整合測試：真實 opencode serve + 模擬 LLM 伺服器，執行 App 的網路程式碼
# 需要：JDK 17+、Gradle 8、Python 3、Node.js（npx 會下載 opencode-ai）
set -e
cd "$(dirname "$0")"
python3 mock/mockllm.py 8787 & P1=$!
mkdir -p /tmp/aichathub-ocwork && cp mock/opencode.json /tmp/aichathub-ocwork/
(cd /tmp/aichathub-ocwork && OPENCODE_SERVER_PASSWORD=testpw npx -y opencode-ai serve --hostname 127.0.0.1 --port 4096) & P3=$!
trap 'kill $P1 $P3 2>/dev/null' EXIT
for i in $(seq 1 60); do curl -s -o /dev/null -u opencode:testpw http://127.0.0.1:4096/doc && break; sleep 1; done
gradle -q run
