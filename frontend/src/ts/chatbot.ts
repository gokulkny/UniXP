import { apiFetch, getToken } from "./api.js";

type ChatRequest = {
  message: string;
  strictFromDocs?: boolean;
};

type ChatResponse = {
  reply?: string;
  error?: string;
};

type ChatMessage = {
  role: "user" | "assistant";
  content: string;
};

const chatFab = document.getElementById("chatFab") as HTMLButtonElement | null;
const chatPanel = document.getElementById("chatPanel") as HTMLElement | null;
const chatCloseBtn = document.getElementById("chatCloseBtn") as HTMLButtonElement | null;
const chatMessages = document.getElementById("chatMessages") as HTMLDivElement | null;
const chatForm = document.getElementById("chatForm") as HTMLFormElement | null;
const chatInput = document.getElementById("chatInput") as HTMLInputElement | null;
const chatHint = document.getElementById("chatHint") as HTMLDivElement | null;
const chatStrictDocs = document.getElementById("chatStrictDocs") as HTMLInputElement | null;

const TOKEN_KEY = "portal_token";

let isOpen = false;
let isSending = false;
const localHistory: ChatMessage[] = [];

function appendMessage(role: ChatMessage["role"], content: string) {
  if (!chatMessages) return;
  const bubble = document.createElement("div");
  bubble.className = `chatBubble ${role}`;
  bubble.textContent = content;
  chatMessages.appendChild(bubble);

  const meta = document.createElement("div");
  meta.className = "chatMeta";
  meta.textContent = role === "user" ? "You" : "Assistant";
  bubble.after(meta);

  chatMessages.scrollTop = chatMessages.scrollHeight;
}

function setHint(message: string) {
  if (!chatHint) return;
  chatHint.textContent = message;
  chatHint.hidden = false;
}

function clearHint() {
  if (!chatHint) return;
  chatHint.textContent = "";
  chatHint.hidden = true;
}

function openPanel() {
  if (!chatPanel) return;
  chatPanel.style.display = "block";
  isOpen = true;
  clearHint();
}

function closePanel() {
  if (!chatPanel) return;
  chatPanel.style.display = "none";
  isOpen = false;
}

async function sendMessage(message: string) {
  if (!chatMessages) return;
  if (!message.trim()) return;
  if (isSending) return;
  isSending = true;

  // UI: optimistic append of user's message
  appendMessage("user", message);
  localHistory.push({ role: "user", content: message });

  const typingBubble = document.createElement("div");
  typingBubble.className = "chatBubble assistant";
  typingBubble.textContent = "Typing...";
  chatMessages.appendChild(typingBubble);

  try {
    const token = getToken() ?? window.localStorage.getItem(TOKEN_KEY);
    const req: ChatRequest = { message, strictFromDocs: Boolean(chatStrictDocs?.checked) };
    const res = await apiFetch<ChatResponse>("/api/chat", {
      method: "POST",
      body: JSON.stringify(req),
    }, token ?? undefined);

    const reply = res.reply ?? res.error;
    if (!reply) throw new Error("Empty response from server.");

    // Replace typing bubble
    typingBubble.textContent = reply;
    localHistory.push({ role: "assistant", content: reply });
    // Add meta under the bubble
    const meta = document.createElement("div");
    meta.className = "chatMeta";
    meta.textContent = "Assistant";
    typingBubble.after(meta);
    chatMessages.scrollTop = chatMessages.scrollHeight;
  } catch (err) {
    // Replace typing bubble with error
    const message = err instanceof Error ? err.message : "Chat failed";
    typingBubble.textContent = message;

    // If unauthorized, redirect to login
    const status = (err as any).status as number | undefined;
    if (status === 401) {
      window.location.href = "/login.html";
    } else {
      setHint("Could not contact the assistant. Try again.");
    }
  } finally {
    isSending = false;
    if (chatInput) chatInput.value = "";
  }
}

chatFab?.addEventListener("click", () => {
  if (!chatPanel) return;
  if (!isOpen) openPanel();
  else closePanel();
});

chatCloseBtn?.addEventListener("click", () => closePanel());

chatForm?.addEventListener("submit", (e) => {
  e.preventDefault();
  if (!chatInput) return;
  const message = chatInput.value;
  void sendMessage(message);
});

// If dashboard loaded without a token, hide the widget to reduce confusion.
if (!getToken() && chatPanel) {
  chatPanel.style.display = "none";
}

