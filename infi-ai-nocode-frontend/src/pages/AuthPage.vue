<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowRight, Sparkles } from "lucide-vue-next";
import { api } from "../api/client";
import { user } from "../stores/session";
import type { User } from "../types";
const route = useRoute(),
  router = useRouter(),
  register = ref(false),
  account = ref(""),
  password = ref(""),
  confirm = ref(""),
  busy = ref(false),
  error = ref("");
async function submit() {
  busy.value = true;
  error.value = "";
  try {
    if (register.value)
      await api("/users/register", "POST", {
        userAccount: account.value,
        userPassword: password.value,
        confirmPassword: confirm.value,
      });
    user.value = await api<User>("/users/login", "POST", {
      userAccount: account.value,
      userPassword: password.value,
    });
    const next = String(route.query.redirect || "/");
    router.push(next.startsWith("/") && !next.startsWith("//") ? next : "/");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="auth-layout">
    <div class="auth-story">
      <span class="eyebrow">A LITTLE IDEA. A BIG POSSIBILITY.</span>
      <h1>你的下一次创作，<br />从这里开始。</h1>
      <div class="orbit">
        <Sparkles :size="68" /><span class="orbit-chip one">想法 + AI</span
        ><span class="orbit-chip two">无限可能 ↗</span>
      </div>
      <p>无需从零编写代码，<br />只需让你的想法被看见。</p>
    </div>
    <form class="auth-form" @submit.prevent="submit">
      <span class="section-kicker">欢迎来到造物 AI</span>
      <h2>{{ register ? "创建你的账号" : "欢迎回来" }}</h2>
      <p class="muted">
        {{ register ? "开启属于你的创作空间" : "登录后继续你的精彩创作" }}
      </p>
      <label
        >账号<input
          v-model="account"
          autocomplete="username"
          required
          minlength="4"
          maxlength="32"
          placeholder="4–32 位字母、数字或下划线" /></label
      ><label
        >密码<input
          v-model="password"
          type="password"
          :autocomplete="register ? 'new-password' : 'current-password'"
          required
          minlength="8"
          maxlength="64"
          placeholder="输入至少 8 位密码" /></label
      ><label v-if="register"
        >确认密码<input
          v-model="confirm"
          type="password"
          autocomplete="new-password"
          required
          placeholder="再次输入密码"
      /></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button class="button dark full" :disabled="busy">
        {{ busy ? "处理中…" : register ? "注册并登录" : "登录"
        }}<ArrowRight :size="17" />
      </button>
      <p class="auth-switch">
        {{ register ? "已有账号？" : "还没有账号？"
        }}<button
          type="button"
          @click="
            register = !register;
            error = '';
          "
        >
          {{ register ? "立即登录" : "创建账号" }}
        </button>
      </p>
    </form>
  </section>
</template>
