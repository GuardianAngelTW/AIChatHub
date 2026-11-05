# AI Chat Hub（Android）

一個 App 串接各家雲端 AI 平台與本地模型：可以用**帳號登入**或 **API Key**。

## 支援的服務

首次開啟 App 會自動建立以下 **7 個服務**，填入金鑰或登入即可使用：

| 服務 | 驗證方式 | 取得方式 |
|---|---|---|
| **GPT（OpenAI）** | API Key | platform.openai.com/api-keys |
| **Claude（Anthropic）** | API Key | console.anthropic.com |
| **Gemini（Google）** | API Key | aistudio.google.com/apikey（有免費額度） |
| **GitHub Copilot** | GitHub 帳號登入（裝置碼） | 需 Copilot 訂閱（含免費版），可用 GPT / Claude / Gemini 等 |
| **Grok（xAI）** | API Key | console.x.ai |
| **OpenRouter** | OpenRouter 帳號登入 | 登入後自動取得金鑰，數百個模型 |
| **LM Studio（本地）** | 不需要 | 電腦開 LM Studio Server，填區網 IP |

按「＋」還可新增：OpenCode Zen、OpenCode Zen（Claude 模型）、OpenCode Server、Ollama、Groq、DeepSeek、Mistral、Together AI、自訂 OpenAI 相容端點。
刪掉預設服務後，可在「AI 服務設定」右上角按「補回預設」。

> **GitHub Copilot 說明**：使用與 OpenCode 等開源客戶端相同的方式（GitHub Device Flow → Copilot token → `api.githubcopilot.com`）。此為 GitHub 未公開文件的介面，可能隨時變更，請自行評估是否符合 GitHub 服務條款。

支援四種協定：**OpenAI 相容**、**Anthropic Messages**、**Gemini generateContent**、**OpenCode Server API**。

## 🤖 AI Agent 功能

全部採用**開放協定與官方 API**：OpenAI / Anthropic / Gemini 的 tool calling、MCP、A2A、Tavily / Brave / SearXNG。不爬取 Google 等搜尋引擎結果頁，也不使用任何受版權保護的素材。

| 功能 | 說明 |
|---|---|
| **Agent 模式** | 對話頂端選單選擇 Agent 後，模型會自主決定是否呼叫工具，執行多步驟推理（ReAct / tool-calling 迴圈）。每一步會以卡片即時顯示，點一下可以展開 |
| **上網搜尋** `web_search` | 支援 Tavily、Brave Search（官方 API，都有免費額度）或自架 SearXNG |
| **讀取網頁** `fetch_url` | 抓取網頁並轉成純文字，讓 Agent 閱讀原文、附上來源 |
| **MCP** | 連線任何遠端 Model Context Protocol 伺服器（Streamable HTTP），把它的工具交給 Agent，例如 GitHub、Notion、資料庫、自建工具 |
| **A2A** | 輸入遠端代理網址，App 讀取 Agent Card（`/.well-known/agent-card.json`）後，可以用 JSON-RPC `message/send` 委派任務給它（相容 v0.3，並嘗試相容 v1.0 的 `SendMessage`） |
| **多代理團隊** | 任何 Agent 都能設定「團隊成員」，透過 `ask_*` 工具把子任務委派給成員，成員也能再往下委派（最多 3 層）。每個 Agent 可以用不同的服務與模型，例如 Claude 當經理、Gemini 當研究員 |
| **跨模型** | Agent 可以跑在 GPT、Claude、Gemini、Copilot、Grok、OpenRouter、LM Studio（需要支援 tool calling 的模型）上 |

### 內建 Agent（首次啟動自動建立）
- **上網助理**：單一 Agent，會搜尋並附上來源
- **專案經理（多代理團隊）**：拆解需求，依序分派給 **研究員 → 撰稿人 → 審稿人**，最後整合交付
- **研究員**、**撰稿人**、**審稿人**：團隊成員，也可以單獨使用

### 快速開始
1. 側邊欄 →「工具：搜尋 / MCP / A2A」→ 選 Tavily 或 Brave，貼上 API Key → 儲存 → 測試搜尋
2. 回到對話，點頂端標題 → 模式選「🤖 專案經理（多代理團隊）」→ 選服務與模型 → 確定
3. 輸入需求，例如「幫我研究 2026 年台灣電動機車市場並寫一份報告」

> 小提示：使用 OpenRouter 時，在模型名稱後面加 `:online`（例如 `openai/gpt-5:online`），就能使用 OpenRouter 內建的聯網搜尋。

## 功能

- 串流輸出（SSE），可中途停止、重新產生
- 多服務多模型即時切換，自動抓取模型清單（同時用來測試連線）
- 每個對話可設系統提示詞（System Prompt）
- 對話紀錄存在手機本機（JSON），側邊欄管理
- API Key 以 **Android Keystore AES-256-GCM** 加密儲存，不會上傳到任何第三方
- Material 3 + 動態配色、深色模式

## 使用 OpenCode

### OpenCode Zen（最簡單）
1. 到 opencode.ai 登入並建立 Zen API Key
2. App →「AI 服務設定」→ ＋ →「OpenCode Zen」→ 貼上 Key →「測試連線」抓取模型 → 儲存
3. Claude 系列模型請另外新增「OpenCode Zen（Claude 模型）」

### OpenCode Server（用 OpenCode 帳號登入的所有供應商）
在電腦或雲端主機上：
```bash
opencode auth login        # 或在 TUI 內 /connect，登入 Claude、Copilot、OpenAI 等
OPENCODE_SERVER_PASSWORD=你的密碼 opencode serve --hostname 0.0.0.0 --port 4096
```
App →「OpenCode Server」→ Base URL 填 `http://電腦IP:4096`，密碼欄填上面的密碼 →「測試連線」。
模型格式為 `providerID/modelID`（例如 `anthropic/claude-sonnet-5-5`），留空則用 OpenCode 預設模型。
OpenCode Server 會在伺服器端保存對話與執行工具，回覆在 agent 完成後一次顯示。

> 對外網路暴露時請務必設定密碼，並建議放在 HTTPS 反向代理（Caddy / Cloudflare Tunnel / Tailscale）後面。

## 使用本地模型（Ollama / LM Studio）
- Ollama：`OLLAMA_HOST=0.0.0.0 ollama serve`，Base URL 填 `http://電腦IP:11434/v1`
- LM Studio：Developer → Start Server → 開啟 Serve on Local Network，Base URL `http://電腦IP:1234/v1`
- 手機與電腦需在同一個 Wi-Fi（或用 Tailscale）

## 版本 1.1.0 修正與測試

**安裝失敗的原因與修正**：舊版每次在 GitHub Actions 建置都會產生不同的隨機簽章，新 APK 和手機上已安裝的版本簽章不同，Android 就會拒絕安裝（「應用程式未安裝」或「與現有套件衝突」）。1.1.0 起專案內附固定簽章 `app/aichathub.keystore`，之後每次建置簽章都相同，可直接覆蓋更新。
**升級到 1.1.0 時請先解除安裝舊版一次**（簽章換了），之後就不需要了。

**OpenCode Server 修正**：
- 伺服器沒設密碼時也能使用（密碼欄留空）
- 「測試連線」不再自動選第一個模型（舊版會選到未登入的供應商，例如 amazon-bedrock，導致每次都失敗）；預設模型留空 = 使用 OpenCode 自己的預設模型
- 模型清單把各供應商的預設模型排在最前面
- 連不上時顯示具體原因（同一 Wi-Fi、`--hostname 0.0.0.0`、防火牆）

**已實際測試**（`tools/protocol-tests`，28 項全部通過）：
- 真實 `opencode serve` 1.19.5：列模型、建立 session、沿用 session、模型留空、帳號:密碼、錯誤密碼
- OpenAI 相容 / Anthropic / Gemini 的串流、模型清單與 tool calling：使用嚴格檢查格式的本地模擬伺服器（測試環境無法連到真實雲端 API）
- Agent 工具迴圈、MCP（JSON 與 SSE 回應）、A2A、多代理委派：使用本地模擬伺服器
- 尚未測試：Android 介面（測試環境無法下載 Android SDK）、真實雲端服務、OpenCode Zen（測試環境無法連到 opencode.ai）

### 用 OpenCode Server 的檢查清單
1. 電腦上啟動：`opencode serve --hostname 0.0.0.0 --port 4096`（要設密碼就在前面加 `OPENCODE_SERVER_PASSWORD=密碼`）
2. 在電腦上先確認可以用：`curl http://127.0.0.1:4096/config/providers`
3. 確認 OpenCode 至少登入了一個供應商（`opencode auth login`），或使用內建免費模型
4. App 的 Base URL 填 `http://電腦區網IP:4096`（不是 127.0.0.1），手機和電腦在同一個 Wi-Fi
5. 按「測試連線」→ 成功後直接對話（預設模型可以留空）

## 在雲端建置 APK（不需要安裝 Android Studio）

專案附 GitHub Actions：
1. 把此資料夾推到一個 GitHub repo（main 分支）
2. Actions 會自動執行「Build APK」，或在 Actions 頁手動 Run workflow
3. 完成後在該次執行的 **Artifacts** 下載 `AIChatHub-debug-apk`，解壓後安裝到手機（需允許安裝未知來源 App）

本機建置：安裝 Android SDK（API 35）後執行 `./gradlew assembleDebug`。

## 專案結構

```
app/src/main/java/com/aichathub/
├── MainActivity.kt          # 入口、OAuth 回呼（aichathub://oauth/...）
├── data/
│   ├── Models.kt            # ProviderConfig / Conversation / ChatMessage
│   ├── Presets.kt           # 各平台預設範本
│   ├── SecureStore.kt       # Keystore 加密金鑰
│   └── Repository.kt        # 本機儲存
├── net/
│   ├── ChatClient.kt        # 四種協定的串流對話與模型清單
│   ├── CopilotAuth.kt       # GitHub Copilot 裝置碼登入
│   ├── AgentCompletion.kt   # 三種協定的 tool calling 編碼與解析
│   ├── AgentRunner.kt       # Agent 迴圈、多代理委派
│   ├── WebTools.kt          # 上網搜尋、讀取網頁
│   ├── McpClient.kt         # MCP 客戶端（Streamable HTTP）
│   └── A2AClient.kt         # A2A 客戶端
│   └── OpenRouterAuth.kt    # OpenRouter OAuth PKCE 登入
└── ui/
    ├── ChatViewModel.kt
    ├── App.kt               # 主題與畫面切換
    ├── ChatScreen.kt        # 對話、側邊欄、模型選擇
    └── ProviderScreens.kt   # 服務清單與編輯
```

## 後續可擴充
- Markdown／程式碼高亮渲染、圖片與檔案輸入（多模態）
- OpenCode Server 的 `/event` SSE 即時顯示 agent 步驟
- Agent 模式的串流輸出、在手機上執行 A2A Server、MCP OAuth 授權流程
- Google 帳號 OAuth（需自行在 Google Cloud 建立 OAuth Client）
- 對話匯出、雲端同步
