import { afterEach, describe, expect, it, vi } from "vitest";
import { checkAccess } from "./access";
import { user, isAdmin } from "../stores/session";

const admin = { id: "1", userAccount: "admin", userName: "Admin", userRole: "admin" as const };
function session(role: "admin" | "user" | null) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
    JSON.stringify(role ? { code: 0, data: { ...admin, userRole: role } } : { code: 401 }),
    { status: role ? 200 : 401 },
  )));
}
afterEach(() => { user.value = null; vi.unstubAllGlobals(); });

describe.each(["/admin/apps", "/admin/users"])("admin access: %s", (fullPath) => {
  const target = { fullPath, meta: { auth: true, admin: true } };
  it("redirects anonymous visitors to login", async () => {
    session(null);
    expect(await checkAccess(target)).toEqual({ path: "/login", query: { redirect: fullPath } });
    expect(isAdmin.value).toBe(false);
  });
  it("rejects regular users", async () => {
    session("user");
    expect(await checkAccess(target)).toBe("/");
    expect(isAdmin.value).toBe(false);
  });
  it("allows administrators", async () => {
    session("admin");
    expect(await checkAccess(target)).toBeUndefined();
    expect(isAdmin.value).toBe(true);
  });
  it("rechecks cached administrator roles", async () => {
    user.value = admin;
    session("user");
    expect(await checkAccess(target)).toBe("/");
    expect(isAdmin.value).toBe(false);
  });
  it("rejects expired administrator sessions", async () => {
    user.value = admin;
    session(null);
    expect(await checkAccess(target)).toEqual({ path: "/login", query: { redirect: fullPath } });
  });
  it("cancels navigation when identity cannot be verified", async () => {
    user.value = admin;
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    expect(await checkAccess(target)).toBe(false);
  });
});
