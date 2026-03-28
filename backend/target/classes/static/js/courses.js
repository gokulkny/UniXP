import { apiFetch, renderCoursesTable } from "./api.js";
const tbody = document.getElementById("coursesTableBody");
const errorEl = document.getElementById("coursesError");
async function loadCourses() {
    if (!tbody)
        return;
    try {
        if (errorEl) {
            errorEl.hidden = true;
            errorEl.textContent = "";
        }
        const courses = await apiFetch("/api/courses", { method: "GET" });
        renderCoursesTable(courses, tbody);
    }
    catch (err) {
        if (!errorEl)
            return;
        const message = err instanceof Error ? err.message : "Failed to load courses";
        errorEl.textContent = message;
        errorEl.hidden = false;
    }
}
loadCourses();
