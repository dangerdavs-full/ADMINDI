/** Compare yyyy-MM strings lexicographically (same length). */
export function clampMonthYear(value: string, min: string, max: string): string {
  if (value < min) return min;
  if (value > max) return max;
  return value;
}

export function defaultMonthYearInBounds(min: string, max: string): string {
  const now = new Date();
  const cur = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  return clampMonthYear(cur, min, max);
}