import { createRouter, createWebHistory } from "vue-router";
import HomePage from "../pages/HomePage.vue";
import AuthPage from "../pages/AuthPage.vue";

import WorkbenchPage from "../pages/WorkbenchPage.vue";
import ProfilePage from "../pages/ProfilePage.vue";
import AdminPage from "../pages/AdminPage.vue";
import AdminMessagesPage from "../pages/AdminMessagesPage.vue";
import { checkAccess } from "./access";
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: HomePage },
    { path: "/login", component: AuthPage },
    { path: "/apps", redirect: "/" },
    { path: "/apps/:id", component: WorkbenchPage, meta: { auth: true } },
    { path: "/profile", component: ProfilePage, meta: { auth: true } },
    {
      path: "/admin/:tab(users|apps)",
      component: AdminPage,
      meta: { auth: true, admin: true },
    },
    {
      path: "/admin/messages",
      component: AdminMessagesPage,
      meta: { auth: true, admin: true },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(checkAccess);
export default router;
