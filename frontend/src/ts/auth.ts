import { apiFetch, setToken } from "./api.js";

type LoginRequest = {
  username: string;
  password: string;
};

const loginForm = document.getElementById("loginForm") as HTMLFormElement | null;
const loginError = document.getElementById("loginError") as HTMLDivElement | null;

function showError(message: string) {
  if (!loginError) return;
  loginError.textContent = message;
  loginError.hidden = false;
}

if (loginForm) {
  loginForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!loginError) return;

    try {
      const usernameInput = document.getElementById("username") as HTMLInputElement | null;
      const passwordInput = document.getElementById("password") as HTMLInputElement | null;
      const username = usernameInput?.value?.trim() ?? "";
      const password = passwordInput?.value ?? "";

      const req: LoginRequest = { username, password };

      // Call demo API.
      const res = await apiFetch<{ token: string; name: string }>("/api/login", {
        method: "POST",
        body: JSON.stringify(req),
      });

      setToken(res.token);
      window.location.href = "/dashboard.html";
    } catch (err) {
      const message = err instanceof Error ? err.message : "Login failed";
      showError(message);
    }
  });
}

