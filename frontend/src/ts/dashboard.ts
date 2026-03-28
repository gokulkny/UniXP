import { apiFetch, Course, getToken, renderCoursesTable } from "./api.js";
import type { MeResponse } from "./api.js";

const meNameEl = document.getElementById("meName") as HTMLSpanElement | null;
const coursesTbody = document.getElementById("dashboardCoursesBody") as HTMLTableSectionElement | null;
const dashboardError = document.getElementById("dashboardError") as HTMLDivElement | null;
const logoutBtn = document.getElementById("logoutBtn") as HTMLButtonElement | null;

function showError(message: string) {
  if (!dashboardError) return;
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
    const me = await apiFetch<MeResponse>("/api/me", { method: "GET" }, token);
    if (meNameEl) meNameEl.textContent = me.name;

    if (coursesTbody) {
      const courses = await apiFetch<Course[]>("/api/courses", { method: "GET" }, token);
      renderCoursesTable(courses, coursesTbody);
    }
  } catch (err) {
    const message = err instanceof Error ? err.message : "Failed to load dashboard";
    // If token is invalid, redirect to login (401 is expected in the demo).
    const status = (err as any).status as number | undefined;
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

