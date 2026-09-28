<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Sparkles,
  ArrowUpRight,
  LogOut,
  LayoutGrid,
  Settings2,
} from "lucide-vue-next";
import { user, isAdmin, loadSession, logout } from "./stores/session";
const route = useRoute(),
  router = useRouter(),
  error = ref("");
onMounted(() =>
  loadSession().catch(() => {
    error.value = "暂时无法连接服务，请确认后端已启动";
  }),
);
async function exit() {
  try {
    await logout();
    await router.push("/");
  } catch (e) {
    error.value = (e as Error).message;
  }
}
</script>
<template>
  <div class="shell">
    <header class="topbar">
      <RouterLink to="/" class="brand"
        ><span class="brand-icon"><Sparkles :size="22" /></span
        ><span>造物 <span class="brand-ai">AI</span></span
        ><span class="brand-tag">NO CODE</span></RouterLink
      >
      <nav>
        <RouterLink to="/" :class="{ active: route.path === '/' }"
          >创作空间</RouterLink
        
        >
        <template v-if="isAdmin">
          <RouterLink to="/admin/apps" :class="{ active: route.path === '/admin/apps' }"
            >应用管理</RouterLink
          >
          <RouterLink to="/admin/users" :class="{ active: route.path === '/admin/users' }"
            >用户管理</RouterLink
          >
        </template>
      </nav>
      <div class="nav-right" v-if="user">
        <RouterLink
          v-if="isAdmin"
          to="/admin/apps"
          class="icon-button"
          title="管理后台"
          ><Settings2 :size="19" /></RouterLink
        ><RouterLink to="/profile" class="user-chip"
          ><span class="avatar">{{
            (user.userName || user.userAccount).slice(0, 1).toUpperCase()
          }}</span
          >{{ user.userName }}</RouterLink
        ><button class="icon-button" title="退出登录" @click="exit">
          <LogOut :size="18" />
        </button>
      </div>
      <RouterLink v-else to="/login" class="button small dark"
        >登录 / 注册 <ArrowUpRight :size="16"
      /></RouterLink>
    </header>
    <div v-if="error" class="global-error" role="alert">
      {{ error }}<button @click="error = ''">关闭</button>
    </div>
    <main :class="{ 'home-main': route.path === '/' }"><RouterView :key="route.path" /></main>
    <footer v-if="!/^\/apps\/\d+/.test(route.path)">
      <span><LayoutGrid :size="14" /> 造物 AI · 让想法成为作品</span
      ><span>DESIGNED FOR YOUR NEXT IDEA</span>
    </footer>
  </div>
</template>
