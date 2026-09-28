<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Search, X, ShieldCheck } from "lucide-vue-next";
import { api } from "../api/client";
import type { User, AppItem, Page } from "../types";
const route = useRoute(),
  router = useRouter(),
  tab = computed(() => String(route.params.tab)),
  rows = ref<(User | AppItem)[]>([]),
  page = ref(1),
  total = ref(0),
  query = ref(""),
  error = ref(""),
  busy = ref(false),
  editing = ref<User | AppItem | null>(null),
  name = ref(""),
  role = ref("user"),
  priority = ref(0);
const account = ref(""),
  username = ref(""),
  type = ref(""),
  filterPriority = ref(""),
  owner = ref("");
const users = computed(() =>
    rows.value.filter((r): r is User => "userAccount" in r),
  ),
  apps = computed(() => rows.value.filter((r): r is AppItem => "appName" in r));
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const params = new URLSearchParams({
      page: String(page.value),
      query: query.value,
      account: account.value,
      name: username.value,
      codeGenType: type.value,
      userId: owner.value,
    });
    if (filterPriority.value !== "")
      params.set("priority", filterPriority.value);
    const result = await api<Page<User | AppItem>>(
      `/admin/${tab.value}?${params}`,
    );
    rows.value = result.records;
    total.value = result.total;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
function reset() {
  query.value = "";
  account.value = "";
  username.value = "";
  type.value = "";
  filterPriority.value = "";
  owner.value = "";
  page.value = 1;
  load();
}
function edit(row: User | AppItem) {
  editing.value = row;
  name.value = "userName" in row ? row.userName : row.appName;
  role.value = "userRole" in row ? row.userRole : "user";
  priority.value = "priority" in row ? row.priority : 0;
}
async function save() {
  if (!editing.value) return;
  try {
    await api(
      `/admin/${tab.value}/${editing.value.id}`,
      "PUT",
      tab.value === "users"
        ? { userName: name.value, userRole: role.value }
        : { appName: name.value, priority: priority.value },
    );
    editing.value = null;
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function feature(app: AppItem) {
  try {
    await api(`/admin/apps/${app.id}`, "PUT", {
      appName: app.appName,
      priority: app.priority > 0 ? 0 : 99,
    });
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function remove(row: User | AppItem) {
  if (!confirm("确定删除？关联的应用或公开链接将停止访问。")) return;
  try {
    await api(`/admin/${tab.value}/${row.id}`, "DELETE");
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
onMounted(load);
</script>
<template>
  <section class="section-width page-section admin-width">
    <span class="section-kicker"
      ><ShieldCheck :size="14" /> ADMINISTRATION</span
    >
    <h1>管理后台</h1>
    <div class="admin-tabs">
      <RouterLink to="/admin/apps" :class="{ selected: tab === 'apps' }"
        >应用管理</RouterLink
      ><RouterLink to="/admin/messages">对话管理</RouterLink
      ><RouterLink to="/admin/users" :class="{ selected: tab === 'users' }"
        >用户管理</RouterLink
      >
    </div>
    <form
      class="admin-filters"
      @submit.prevent="
        page = 1;
        load();
      "
    >
      <template v-if="tab === 'users'"
        ><input
          v-model="account"
          placeholder="搜索账号"
          aria-label="搜索账号" /><input
          v-model="username"
          placeholder="搜索用户名"
          aria-label="搜索用户名"
      /></template>
      <template v-else
        ><input
          v-model="query"
          placeholder="应用名称"
          aria-label="应用名称" /><select v-model="type" aria-label="生成类型">
          <option value="">全部生成类型</option>
          <option value="html">原生 HTML</option>
          <option value="multi_file">原生多文件</option>
          <option value="vue_project">Vue 工程</option></select
        ><input
          v-model="filterPriority"
          type="number"
          min="0"
          placeholder="优先级"
          aria-label="优先级" /><input
          v-model="owner"
          placeholder="用户 ID"
          aria-label="用户 ID"
      /></template>
      <button class="button small dark"><Search :size="14" />搜索</button
      ><button type="button" class="button small secondary" @click="reset">
        重置
      </button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="table-panel">
      <table v-if="tab === 'users'">
        <thead>
          <tr>
            <th>ID</th>
            <th>账号</th>
            <th>用户名</th>
            <th>头像</th>
            <th>简介</th>
            <th>角色</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in users" :key="row.id">
            <td>{{ row.id }}</td>
            <td>{{ row.userAccount }}</td>
            <td>{{ row.userName }}</td>
            <td>
              <img
                v-if="row.userAvatar"
                :src="row.userAvatar"
                class="table-avatar"
                alt="头像"
              /><span v-else>—</span>
            </td>
            <td class="ellipsis" :title="row.userProfile">
              {{ row.userProfile || "—" }}
            </td>
            <td>
              <span class="tag">{{ row.userRole }}</span>
            </td>
            <td>{{ row.createTime?.replace("T", " ") }}</td>
            <td>
              <button class="table-action" @click="edit(row)">编辑</button
              ><button class="table-action danger-text" @click="remove(row)">
                删除
              </button>
            </td>
          </tr>
          <tr v-if="!users.length">
            <td colspan="8" class="empty">
              {{ busy ? "加载中…" : "没有匹配的记录" }}
            </td>
          </tr>
        </tbody>
      </table>
      <table v-else>
        <thead>
          <tr>
            <th>ID</th>
            <th>应用名称</th>
            <th>封面</th>
            <th>初始需求</th>
            <th>生成类型</th>
            <th>部署标识</th>
            <th>优先级</th>
            <th>用户 ID</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in apps" :key="row.id">
            <td>{{ row.id }}</td>
            <td>
              <button
                class="table-action"
                @click="router.push(`/apps/${row.id}`)"
              >
                {{ row.appName }}
              </button>
            </td>
            <td>
              <img
                v-if="row.cover"
                :src="row.cover"
                class="table-cover"
                alt="封面"
              /><span v-else>—</span>
            </td>
            <td class="ellipsis" :title="row.initPrompt">
              {{ row.initPrompt }}
            </td>
            <td>
              <span class="tag">{{ row.codeGenType }}</span>
            </td>
            <td class="ellipsis" :title="row.deployKey">
              {{ row.deployKey || "未部署" }}
            </td>
            <td>{{ row.priority }}</td>
            <td>{{ row.userId }}</td>
            <td>{{ row.createTime.replace("T", " ") }}</td>
            <td class="actions-cell">
              <button class="table-action" @click="edit(row)">编辑</button
              ><button
                class="table-action feature-action"
                :class="{ 'is-featured': row.priority > 0 }"
                :aria-pressed="row.priority > 0"
                @click="feature(row)"
              >
                {{ row.priority > 0 ? "取消精选" : "设为精选" }}</button
              ><button class="table-action danger-text" @click="remove(row)">
                删除
              </button>
            </td>
          </tr>
          <tr v-if="!apps.length">
            <td colspan="10" class="empty">
              {{ busy ? "加载中…" : "没有匹配的记录" }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination">
      <button
        :disabled="page === 1"
        @click="
          page--;
          load();
        "
      >
        上一页</button
      ><span>第 {{ page }} 页 · 共 {{ total }} 条</span
      ><button
        :disabled="page * 20 >= total"
        @click="
          page++;
          load();
        "
      >
        下一页
      </button>
    </div>
  </section>
  <div v-if="editing" class="modal-backdrop" @click.self="editing = null">
    <form class="modal" @submit.prevent="save">
      <div class="section-heading">
        <h2>编辑{{ tab === "users" ? "用户" : "应用" }}</h2>
        <button class="icon-button" type="button" @click="editing = null">
          <X :size="20" />
        </button>
      </div>
      <label
        >{{ tab === "users" ? "昵称" : "应用名称"
        }}<input v-model="name" required maxlength="60" /></label
      ><label v-if="tab === 'users'"
        >角色<select v-model="role">
          <option value="user">普通用户</option>
          <option value="admin">管理员</option>
        </select></label
      ><label v-else
        >优先级（大于 0 且已部署时展示为精选；登录用户可只读查看其对话）<input
          v-model.number="priority"
          type="number"
          min="0"
          max="9999"
          required
      /></label>
      <p v-if="error" class="error">{{ error }}</p>
      <button class="button dark full">保存</button>
    </form>
  </div>
</template>
