# AI Chat Hub（Android）

一個 App 串接各家雲端 AI 平台與本地模型：可以用**帳號登入**或 **API Key**。

## 支援的服務

| 類型 | 服務 | 驗證方式 |
|---|---|---|
| 雲端（登入） | **OpenRouter**：一個帳號就能用數百個模型（GPT、Claude、Gemini、Llama、DeepSeek…） | OAuth PKCE 登入，自動取得金鑰 |
| 雲端（OpenCode） | **OpenCode Zen**：OpenCode 官方精選模型閘道，`https://opencode.ai/zen/v1`（含免費模型） | API Key |
| 雲端（OpenCode） | **OpenCode Zen（Claude 模型）**：Anthropic 協定，同一個 Zen 端點 | API Key |
| 自架（OpenCode） | **OpenCode Server**（`opencode serve`）：可使用你在 OpenCode 裡 `/connect` 登入的所有供應商，包含 Claude Pro/Max、GitHub Copilot 等**帳號登入** | 伺服器密碼（HTTP Basic） |
| 雲端 | OpenAI、Anthropic Claude、Google Gemini、Groq、DeepSeek、Mistral、xAI、Together | API Key |
| 本地 | Ollama、LM Studio、vLLM、LiteLLM 或任何 OpenAI 相容端點 | 不需要／自訂 |

支援四種協定：**OpenAI 相容**、**Anthropic Messages**、**Gemini generateContent**、**OpenCode Server API**。

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
- Google 帳號 OAuth（需自行在 Google Cloud 建立 OAuth Client）
- 對話匯出、雲端同步

## 文檔

- **使用說明與技術手冊**（安裝、服務設定、Agent／MCP／A2A、協定實作、擴充指南）：
  - Markdown：[docs/AIChatHub_使用說明與技術手冊.md](docs/AIChatHub_使用說明與技術手冊.md)
  - PDF：[docs/AIChatHub_使用說明與技術手冊.pdf](docs/AIChatHub_使用說明與技術手冊.pdf)
  - Word：[docs/AIChatHub_使用說明與技術手冊.docx](docs/AIChatHub_使用說明與技術手冊.docx)
- **架構圖**：[docs/AIChatHub_架構圖.png](docs/AIChatHub_架構圖.png)

## 授權

本專案由 Claude（AI）從零設計，作為 **AI 協作開發 Android App 的參考範本**。以 MIT License 釋出：**可以自由修改、直接拿來用、拿去做出自己的產品**，無版權負擔。詳見 [LICENSE](LICENSE)。
