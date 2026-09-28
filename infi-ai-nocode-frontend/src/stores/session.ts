import { computed, ref } from "vue";
import { api, ApiError } from "../api/client";
import type { User } from "../types";
export const user = ref<User | null>(null);
export const isAdmin = computed(() => user.value?.userRole === "admin");
export async function loadSession() {
  try {
    user.value = await api<User>("/users/me");
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401)) throw e;
    user.value = null;
  }
}
export async function logout() {
  await api("/users/logout", "POST");
  user.value = null;
}
