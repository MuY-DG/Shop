/** Refresh a loaded list without shrinking it to page one between requests. */
interface ListPage {
  records: unknown[];
  current: number;
  size: number;
  total?: number;
  hasMore?: boolean;
}

export async function reloadListPages<P extends ListPage>(
  lastPage: number,
  fetchPage: (current: number) => Promise<P>,
  isCurrent: () => boolean
): Promise<P | null> {
  let combined: P | null = null;
  const records: unknown[] = [];
  const pageCount = Math.max(1, Math.floor(lastPage) || 1);
  for (let current = 1; current <= pageCount; current += 1) {
    if (!isCurrent()) return null;
    const page = await fetchPage(current);
    if (!isCurrent()) return null;
    records.push(...page.records);
    combined = { ...page, records };
    const hasMore = page.hasMore ?? current * page.size < (page.total || 0);
    if (!hasMore || !page.records.length) break;
  }
  return combined;
}

const requestIds = new WeakMap<object, number>();
const scrollPositions = new WeakMap<object, number>();

export function beginListRequest(owner: object): number {
  const id = (requestIds.get(owner) || 0) + 1;
  requestIds.set(owner, id);
  return id;
}

export function isCurrentListRequest(owner: object, id: number): boolean {
  return requestIds.get(owner) === id;
}

export function rememberListScroll(owner: object, value: number): void {
  scrollPositions.set(owner, Number.isFinite(value) ? Math.max(0, value) : 0);
}

export function listScrollPatch(owner: object, preserve: boolean): { scrollTop: number } {
  if (!preserve) rememberListScroll(owner, 0);
  return { scrollTop: scrollPositions.get(owner) || 0 };
}
