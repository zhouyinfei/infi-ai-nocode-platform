<script setup lang="ts">
import { ref, onMounted } from "vue";
import { ShieldCheck, X } from "lucide-vue-next";
import { api } from "../api/client";
import type { Message, Page } from "../types";
type AdminMessage = Message & { appId: string; userId: string };
const rows = ref<AdminMessage[]>([]),
  query = ref(""),
  appId = ref(""),
  userId = ref(""),
  type = ref(""),
  start = ref(""),
  end = ref(""),
  page = ref(1),
  total = ref(0),
  error = ref(""),
  detail = ref<AdminMessage | null>(null),
  busy = ref(false);
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const params = new URLSearchParams({
      query: query.value,
      appId: appId.value,
      userId: userId.value,
      messageType: type.value,
      page: String(page.value),
    });
    if (start.value) params.set("start", start.value);
    if (end.value) params.set("end", end.value);
    const result = await api<Page<AdminMessage>>(`/admin/messages?${params}`);
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
  appId.value = "";
  userId.value = "";
  type.value = "";
  start.value = "";
  end.value = "";
  page.value = 1;
  load();
}
async function remove(row: AdminMessage) {
  if (!confirm("确定删除这条对话记录？")) return;
  try {
    await api(`/admin/messages/${row.id}`, "DELETE");
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
      <RouterLink to="/admin/apps">应用管理</RouterLink
      ><RouterLink to="/admin/messages" class="selected">对话管理</RouterLink
      ><RouterLink to="/admin/users">用户管理</RouterLink>
    </div>
    <form
      class="admin-filters"
      @submit.prevent="
        page = 1;
        load();
      "
    >
      <input
        v-model="query"
        placeholder="消息内容"
        aria-label="消息内容"
      /><input
        v-model="appId"
        placeholder="应用 ID"
        aria-label="应用 ID"
      /><input
        v-model="userId"
        placeholder="用户 ID"
        aria-label="用户 ID"
      /><select v-model="type" aria-label="消息类型">
        <option value="">全部消息类型</option>
        <option value="user">用户</option>
        <option value="ai">AI</option></select
      ><input
        v-model="start"
        type="datetime-local"
        aria-label="开始时间"
        title="开始时间"
      /><input
        v-model="end"
        type="datetime-local"
        aria-label="结束时间"
        title="结束时间"
      /><button class="button small dark">搜索</button
      ><button type="button" class="button small secondary" @click="reset">
        重置
      </button>
    </form>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="table-panel">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>消息内容</th>
            <th>类型</th>
            <th>应用 ID</th>
            <th>用户 ID</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ row.id }}</td>
            <td class="ellipsis">{{ row.message }}</td>
            <td>
              <span class="tag">{{ row.messageType }}</span>
            </td>
            <td>{{ row.appId }}</td>
            <td>{{ row.userId }}</td>
            <td>{{ row.createTime.replace("T", " ") }}</td>
            <td>
              <button class="table-action" @click="detail = row">查看</button
              ><button class="table-action danger-text" @click="remove(row)">
                删除
              </button>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="7" class="empty">
              {{ busy ? "加载中…" : "暂无对话记录" }}
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
  <div v-if="detail" class="modal-backdrop" @click.self="detail = null">
    <section class="modal wide-modal">
      <div class="section-heading">
        <h2>对话内容</h2>
        <button class="icon-button" @click="detail = null">
          <X :size="20" />
        </button>
      </div>
      <pre class="message-detail">{{ detail.message }}</pre>
    </section>
  </div>
</template>
