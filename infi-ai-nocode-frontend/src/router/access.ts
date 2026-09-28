import type { RouteLocationNormalized } from "vue-router";
import { user, isAdmin, loadSession } from "../stores/session";

export async function checkAccess(to: Pick<RouteLocationNormalized, "meta" | "fullPath">) {
  if (!to.meta.auth && !to.meta.admin) return;
  // Recheck server-side identity on every admin navigation, including role changes.
  if (!user.value || to.meta.admin) {
    try {
      await loadSession();
    } catch {
      return false;
    }
  }
  if (!user.value) return { path: "/login", query: { redirect: to.fullPath } };
  if (to.meta.admin && !isAdmin.value) return "/";
}
