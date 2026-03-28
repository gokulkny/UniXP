const TOKEN_STORAGE_KEY = "portal_token";
export function getToken() {
    return window.localStorage.getItem(TOKEN_STORAGE_KEY);
}
export function setToken(token) {
    window.localStorage.setItem(TOKEN_STORAGE_KEY, token);
}
export function clearToken() {
    window.localStorage.removeItem(TOKEN_STORAGE_KEY);
}
export async function apiFetch(url, init = {}, token) {
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    const maybeToken = token ?? getToken();
    if (maybeToken && !headers.has("Authorization")) {
        headers.set("Authorization", `Bearer ${maybeToken}`);
    }
    if (init.body !== undefined && init.body !== null) {
        if (!headers.has("Content-Type"))
            headers.set("Content-Type", "application/json");
    }
    const response = await fetch(url, { ...init, headers });
    if (!response.ok) {
        // Keep error messages predictable for the demo.
        let message = `${response.status} ${response.statusText}`;
        try {
            const text = await response.text();
            if (text)
                message = text;
        }
        catch {
            // ignore
        }
        const error = new Error(message);
        error.status = response.status;
        throw error;
    }
    return (await response.json());
}
export function renderCoursesTable(courses, tbody) {
    tbody.innerHTML = "";
    for (const course of courses) {
        const tr = document.createElement("tr");
        const tdTitle = document.createElement("td");
        tdTitle.textContent = course.title;
        const tdDept = document.createElement("td");
        tdDept.textContent = course.department;
        const tdCredits = document.createElement("td");
        tdCredits.textContent = String(course.credits);
        const tdSeats = document.createElement("td");
        tdSeats.textContent = String(course.seats);
        tr.appendChild(tdTitle);
        tr.appendChild(tdDept);
        tr.appendChild(tdCredits);
        tr.appendChild(tdSeats);
        tbody.appendChild(tr);
    }
}
