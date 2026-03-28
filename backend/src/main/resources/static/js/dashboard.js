import { apiFetch, getToken, renderCoursesTable } from "./api.js";
const meNameEl = document.getElementById("meName");
const coursesTbody = document.getElementById("dashboardCoursesBody");
const dashboardError = document.getElementById("dashboardError");
const logoutBtn = document.getElementById("logoutBtn");
function showError(message) {
    if (!dashboardError)
        return;
    dashboardError.textContent = message;
    dashboardError.hidden = false;
}
async function loadDashboard() {
    const token = getToken();
    if (!token) {
        window.location.href = "/login.html";
        return;
    }
    try {
        const me = await apiFetch("/api/me", { method: "GET" }, token);
        if (meNameEl)
            meNameEl.textContent = me.name;
        if (coursesTbody) {
            const courses = await apiFetch("/api/courses", { method: "GET" }, token);
            renderCoursesTable(courses, coursesTbody);
        }
    }
    catch (err) {
        const message = err instanceof Error ? err.message : "Failed to load dashboard";
        // If token is invalid, redirect to login (401 is expected in the demo).
        const status = err.status;
        if (status === 401) {
            window.location.href = "/login.html";
            return;
        }
        showError(message);
    }
}
if (logoutBtn) {
    logoutBtn.addEventListener("click", () => {
        window.localStorage.removeItem("portal_token");
        window.location.href = "/login.html";
    });
}
loadDashboard();
