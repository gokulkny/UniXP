import { apiFetch, Course, renderCoursesTable } from "./api.js";

const tbody = document.getElementById("coursesTableBody") as HTMLTableSectionElement | null;
const errorEl = document.getElementById("coursesError") as HTMLDivElement | null;

async function loadCourses() {
  if (!tbody) return;
  try {
    if (errorEl) {
      errorEl.hidden = true;
      errorEl.textContent = "";
    }

    const courses = await apiFetch<Course[]>("/api/courses", { method: "GET" });
    renderCoursesTable(courses, tbody);
  } catch (err) {
    if (!errorEl) return;
    const message = err instanceof Error ? err.message : "Failed to load courses";
    errorEl.textContent = message;
    errorEl.hidden = false;
  }
}

loadCourses();

