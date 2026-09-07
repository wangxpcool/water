export async function requestJson(url, options = {}) {
  const response = await fetch(url, { ...options, headers: { 'Content-Type': 'application/json', ...options.headers } });
  if (!response.ok) {
    const body = await response.text();
    let message = body;
    try { const error = JSON.parse(body); message = error.detail || error.message || error.error; } catch { /* Plain text response. */ }
    const error = new Error(message || `请求失败 (${response.status})`);
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}
