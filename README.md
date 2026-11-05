# AI Chat Hub Lite（Android）

只有對話功能的精簡版：用帳號登入或 API Key 連接各家雲端 AI 平台與本地模型。沒有 Agent、MCP、A2A、上網搜尋。

套件名稱 `com.aichathub.lite`，可以和完整版 AI Chat Hub 同時安裝。

## 支援的服務

首次開啟會自動建立以下 7 個服務，填入金鑰或登入即可使用：

| 服務 | 驗證方式 |
|---|---|
| GPT（OpenAI） | API Key（platform.openai.com/api-keys） |
| Claude（Anthropic） | API Key（console.anthropic.com） |
| Gemini（Google） | API Key（aistudio.google.com/apikey） |
| GitHub Copilot | GitHub 帳號登入（裝置碼，需 Copilot 訂閱） |
| Grok（xAI） | API Key（console.x.ai） |
| OpenRouter | OpenRouter 帳號登入 |
| LM Studio（本地） | 不需要 |

按「＋」還可新增：OpenCode Zen、OpenCode Server、Ollama、Groq、DeepSeek、Mistral、Together AI、自訂 OpenAI 相容端點。

## 功能
- 串流輸出、停止、重新產生
- 多服務多模型切換，自動抓取模型清單
- 每個對話可設系統提示詞
- 對話存在手機本機；API Key 以 Android Keystore 加密
- 連線失敗時顯示具體原因

## 安裝（雲端建置）
1. 把此資料夾推到 GitHub repo 的 `main` 分支
2. Actions 自動執行「Build APK」
3. 在該次執行的 Artifacts 下載 `AIChatHubLite-debug-apk`，解壓後安裝

專案內附固定簽章（`app/aichathub.keystore`），之後每次建置都能直接覆蓋更新。此金鑰僅供個人測試，公開發布請換成自己的金鑰。

## OpenCode Server 檢查清單
1. 電腦上啟動：`opencode serve --hostname 0.0.0.0 --port 4096`（要設密碼就在前面加 `OPENCODE_SERVER_PASSWORD=密碼`）
2. 先在電腦上確認：`curl http://127.0.0.1:4096/config/providers`
3. 確認 OpenCode 至少登入一個供應商（`opencode auth login`），或使用內建免費模型
4. App 的 Base URL 填 `http://電腦區網IP:4096`，手機與電腦在同一個 Wi-Fi；沒設密碼則密碼欄留空
5. 按「測試連線」後直接對話；預設模型可以留空（使用 OpenCode 的預設模型）

## 本地模型
- Ollama：`OLLAMA_HOST=0.0.0.0 ollama serve`，Base URL `http://電腦IP:11434/v1`
- LM Studio：Developer → Start Server → 開啟 Serve on Local Network，Base URL `http://電腦IP:1234/v1`

## 測試
`tools/protocol-tests/run.sh` 會啟動真實的 `opencode serve` 與本地模擬 LLM 伺服器，執行 App 的網路程式碼（15 項，全部通過）。
- 真實測試：OpenCode Server（列模型、建立與沿用 session、模型留空、帳號:密碼、錯誤密碼）
- 模擬伺服器測試：OpenAI 相容、Anthropic、Gemini 的串流與模型清單
- 尚未測試：Android 介面（測試環境無法下載 Android SDK）、真實雲端服務、OpenCode Zen

## 專案結構
```
app/src/main/java/com/aichathub/
├── MainActivity.kt          # 入口、OAuth 回呼（aichathublite://oauth/...）
├── data/                    # 資料模型、服務範本、加密儲存、本機儲存
├── net/
│   ├── ChatClient.kt        # OpenAI 相容 / Anthropic / Gemini / OpenCode 串流與模型清單
│   ├── OpenRouterAuth.kt    # OpenRouter OAuth PKCE
│   └── CopilotAuth.kt       # GitHub Copilot 裝置碼登入
└── ui/                      # 對話畫面、服務設定、ViewModel
```

> GitHub Copilot 使用 GitHub 未公開文件的介面（與 OpenCode 等開源客戶端相同方式），可能隨時變更，請自行評估是否符合 GitHub 服務條款。
