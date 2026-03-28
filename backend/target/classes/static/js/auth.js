import { apiFetch, setToken } from "./api.js";
const loginForm = document.getElementById("loginForm");
const loginError = document.getElementById("loginError");
function showError(message) {
    if (!loginError)
        return;
    loginError.textContent = message;
    loginError.hidden = false;
}
if (loginForm) {
    loginForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        if (!loginError)
            return;
        try {
            const usernameInput = document.getElementById("username");
            const passwordInput = document.getElementById("password");
            const username = usernameInput?.value?.trim() ?? "";
            const password = passwordInput?.value ?? "";
            const req = { username, password };
            // Call demo API.
            const res = await apiFetch("/api/login", {
                method: "POST",
                body: JSON.stringify(req),
            });
            setToken(res.token);
            window.location.href = "/dashboard.html";
        }
        catch (err) {
            const message = err instanceof Error ? err.message : "Login failed";
            showError(message);
        }
    });
}
