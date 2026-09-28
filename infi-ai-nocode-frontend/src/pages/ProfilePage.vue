<script setup lang="ts">
import { ref } from "vue";
import { api } from "../api/client";
import { user } from "../stores/session";
import type { User } from "../types";
const name = ref(user.value?.userName || ""),
  avatar = ref(user.value?.userAvatar || ""),
  profile = ref(user.value?.userProfile || ""),
  status = ref(""),
  error = ref(""),
  busy = ref(false);
async function save() {
  busy.value = true;
  error.value = "";
  status.value = "";
  try {
    user.value = await api<User>("/users/me", "PUT", {
      userName: name.value,
      userAvatar: avatar.value,
      userProfile: profile.value,
    });
    status.value = "个人资料已保存";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="narrow-page">
    <span class="section-kicker">ABOUT YOU</span>
    <h1>个人资料</h1>
    <form class="panel form-stack" @submit.prevent="save">
      <label>账号<input :value="user?.userAccount" disabled /></label
      ><label>昵称<input v-model="name" maxlength="60" required /></label
      ><label
        >头像地址<input
          v-model="avatar"
          type="url"
          maxlength="1024"
          placeholder="https://…" /></label
      ><label
        >个人简介<textarea
          v-model="profile"
          maxlength="512"
          rows="4"
          placeholder="介绍一下自己吧"
        />
      </label>
      <p class="error" v-if="error">{{ error }}</p>
      <p class="success" v-if="status">{{ status }}</p>
      <button class="button dark" :disabled="busy">
        {{ busy ? "保存中…" : "保存修改" }}
      </button>
    </form>
  </section>
</template>
