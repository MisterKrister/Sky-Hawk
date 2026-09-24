export const WEALTH_TTL = 900_000;
export const ABSENT_TTL = 604_800_000;
export type CachedWealth = { name: string; uuid: string; wealth: Record<string, unknown>; fetchedAt: number };

/** Store totals only, never inventories, item NBT, account credentials, or the friend roster. */
export function validateWealth(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const data = value as Record<string, unknown>;
  if (typeof data.hasProfile !== "boolean") return null;
  const result: Record<string, unknown> = { hasProfile: data.hasProfile };
  for (const key of ["networth", "purse", "bank", "wardrobe"]) {
    if (data[key] == null) continue;
    if (!data.hasProfile || typeof data[key] !== "number" || !Number.isFinite(data[key]) ||
        data[key] < 0 || data[key] > Number.MAX_SAFE_INTEGER) return null;
    result[key] = data[key];
  }
  for (const key of ["profile", "status"]) {
    const text = data[key] ?? "";
    if (typeof text !== "string" || text.length > (key === "profile" ? 64 : 128) || /[^\x20-\x7e]/.test(text)) return null;
    result[key] = text;
  }
  return result;
}

export class SharedWealth {
  constructor(private sql: SqlStorage) {
    sql.exec(`CREATE TABLE IF NOT EXISTS player_wealth (
      id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
      name TEXT NOT NULL COLLATE NOCASE UNIQUE, wealth TEXT NOT NULL, fetched_at INTEGER NOT NULL)`);
  }

  get(name: string, uuid: string | undefined, now: number, refresh: boolean): CachedWealth | null {
    const row = this.sql.exec<{ uuid: string; name: string; wealth: string; fetched_at: number }>(
      `SELECT uuid, name, wealth, fetched_at FROM player_wealth WHERE ${uuid ? "uuid" : "name"} = ?`, uuid ?? name,
    ).toArray()[0];
    if (!row || (uuid && row.uuid !== uuid)) return null;
    const wealth = JSON.parse(row.wealth);
    const ttl = refresh ? 60_000 : wealth.hasProfile ? WEALTH_TTL : ABSENT_TTL;
    return row.fetched_at > now - ttl ? { name: row.name, uuid: row.uuid, wealth, fetchedAt: row.fetched_at } : null;
  }

  put(record: CachedWealth, now: number): boolean {
    const existing = this.sql.exec<{ fetched_at: number }>(
      "SELECT fetched_at FROM player_wealth WHERE uuid = ? OR name = ?", record.uuid, record.name,
    ).toArray();
    if (existing.some(row => row.fetched_at >= record.fetchedAt || row.fetched_at > now - 60_000)) return false;
    this.sql.exec("DELETE FROM player_wealth WHERE uuid = ? OR name = ?", record.uuid, record.name);
    this.sql.exec("INSERT INTO player_wealth (uuid, name, wealth, fetched_at) VALUES (?, ?, ?, ?)",
      record.uuid, record.name, JSON.stringify(record.wealth), record.fetchedAt);
    // Bound storage to the latest 10,000 uploads without counting/scanning the whole table per write.
    this.sql.exec("DELETE FROM player_wealth WHERE id <= (SELECT MAX(id) FROM player_wealth) - 10000");
    return true;
  }
}
