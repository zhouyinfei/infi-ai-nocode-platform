import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./client";
afterEach(() => vi.unstubAllGlobals());
describe("API client", () => {
  it("includes session credentials and CSRF preflight header", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ code: 0, data: { id: "9007199254740993" } }),
          { status: 200 },
        ),
      );
    vi.stubGlobal("fetch", fetch);
    expect(await api("/apps", "POST", { initPrompt: "test" })).toEqual({
      id: "9007199254740993",
    });
    expect(fetch).toHaveBeenCalledWith(
      "/api/apps",
      expect.objectContaining({
        credentials: "include",
        headers: expect.objectContaining({ "X-Nocode-Request": "1" }),
      }),
    );
  });
  it("keeps authentication errors distinguishable", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ code: 401, message: "请先登录" }), {
            status: 401,
          }),
        ),
    );
    await expect(api("/users/me")).rejects.toMatchObject({
      status: 401,
      message: "请先登录",
    });
  });
  it("does not treat server HTML as a successful result", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response("<html>error</html>", { status: 502 })),
    );
    await expect(api("/apps")).rejects.toBeInstanceOf(ApiError);
  });
});
