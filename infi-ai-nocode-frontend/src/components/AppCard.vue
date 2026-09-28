<script setup lang="ts">
import { Terminal, MoreHorizontal } from "lucide-vue-next";
import { ref } from "vue";
import type { AppItem } from "../types";
defineProps<{ app: AppItem; index?: number; publicCard?: boolean }>();
defineEmits<{ open: [app: AppItem]; edit: [app: AppItem] }>();
const showPublishHint = ref(false);
</script>
<template>
  <article class="app-card">
    <div
      class="card-preview"
      :class="`cover-${(index || 0) % 4}`"
    >
      <img
        v-if="app.cover"
        :src="app.cover"
        :alt="app.appName"
        loading="lazy"
      />
      <div v-else class="placeholder-window">
        <div class="window-dots"><i></i><i></i><i></i></div>
        <div class="mini-heading">{{ app.appName }}</div>
        <div class="mini-line"></div>
        <div class="mini-blocks"><span></span><span></span><span></span></div>
        <span class="mini-pill">EXPLORE MORE ↗</span>
      </div>
      <div class="card-hover-actions">
        <RouterLink class="card-action conversation" :to="`/apps/${app.id}`">查看对话</RouterLink>
        <a v-if="app.url" class="card-action website" :href="app.url" target="_blank" rel="noopener noreferrer">查看作品</a>
        <button v-else type="button" class="card-action website" @click="showPublishHint = true">查看作品</button>
      </div>
    </div>
    <p v-if="showPublishHint && !app.url" class="card-preview-error" role="status">应用尚未发布，请先进入“查看对话”页面发布作品。</p>
    <div class="card-details">
      <span class="card-avatar"><Terminal :size="23" /></span>
      <div class="card-copy">
        <h3 :title="app.appName">{{ app.appName }}</h3>
        <span v-if="publicCard" class="muted card-creator" :title="app.creatorName || '未命名用户'">{{ app.creatorName || '未命名用户' }}</span>
        <span v-else class="muted">{{ app.createTime ? `创建于 ${new Date(app.createTime).toLocaleDateString('zh-CN')}` : '精选作品' }}</span>
      </div>
      <button
        v-if="!publicCard"
        class="icon-button"
        @click="$emit('edit', app)"
        aria-label="编辑应用"
      >
        <MoreHorizontal :size="20" /></button
      ><span v-else class="tag">精选</span>
    </div>
  </article>
</template>

<style scoped>
.card-creator {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
