<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import {
  Search,
  X,
  ArrowUp,
  Sparkles,
  Globe,
  Layers,
  PanelsTopLeft,
  ArrowRight,
  LoaderCircle,
} from "lucide-vue-next";
import { api } from "../api/client";
import { user } from "../stores/session";
import type { AppItem, Page } from "../types";
import AppCard from "../components/AppCard.vue";
const router = useRouter(),
  prompt = ref(sessionStorage.getItem("draft-prompt") || ""),
  busy = ref(false),
  error = ref(""),
  featured = ref<AppItem[]>([]),
  loading = ref(true);
const featuredQuery = ref(""),
  featuredPage = ref(1),
  featuredTotal = ref(0),
  mine = ref<AppItem[]>([]),
  mineQuery = ref(""), minePage = ref(1), mineTotal = ref(0), mineLoading = ref(false), editing = ref<AppItem | null>(null), name = ref("");
const pageSize = 6;
interface Example {
  id: string;
  title: string;
  description: string;
  codeGenType: string;
  prompt: string;
}
const ideas = ref<Example[]>([]);
const selectedExample = computed(() =>
  ideas.value.find((idea) => idea.prompt === prompt.value.trim()),
);
async function loadExamples() {
  try {
    ideas.value = await api<Example[]>("/apps/examples");
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function create() {
  if (!prompt.value.trim() || busy.value) return;
  sessionStorage.setItem("draft-prompt", prompt.value);
  if (!user.value) {
    router.push("/login?redirect=/");
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const app = await api<AppItem>("/apps", "POST", {
      initPrompt: prompt.value,
      appName: selectedExample.value?.title,
    });
    sessionStorage.removeItem("draft-prompt");
    router.push({ path: `/apps/${app.id}`, query: { start: "1" } });
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function loadFeatured() {
  loading.value = true;
  try {
    const result = await api<Page<AppItem>>(
      `/apps/featured?query=${encodeURIComponent(featuredQuery.value)}&page=${featuredPage.value}&pageSize=${pageSize}`,
    );
    featured.value = result.records;
    featuredTotal.value = result.total;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
async function loadMine() {
  if (!user.value) {
    mine.value = [];
    mineTotal.value = 0;
    return;
  }
  mineLoading.value = true;
  try {
    const result = await api<Page<AppItem>>(
      `/apps?query=${encodeURIComponent(mineQuery.value)}&page=${minePage.value}&pageSize=${pageSize}`,
    );
    mine.value = result.records;
    mineTotal.value = result.total;
    if (minePage.value > 1 && !mine.value.length) {
      minePage.value = Math.max(1, Math.ceil(result.total / pageSize));
      await loadMine();
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    mineLoading.value = false;
  }
}
function edit(app: AppItem) {
  editing.value = app;
  name.value = app.appName;
}
async function save() {
  if (!editing.value) return;
  try {
    await api(`/apps/${editing.value.id}`, "PUT", { appName: name.value });
    editing.value = null;
    await loadMine();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function remove() {
  if (!editing.value || !confirm("删除后该应用及公开访问将不可用，确定删除？"))
    return;
  try {
    await api(`/apps/${editing.value.id}`, "DELETE");
    editing.value = null;
    await loadMine();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
watch(user, loadMine, { immediate: true });
onMounted(loadFeatured);
onMounted(loadExamples);
</script>
<template>
  <section class="hero">
    <h1>AI 应用生成平台</h1>
    <p class="hero-description">
      与 AI 对话，轻松创建应用和网站
    </p>
    <form class="prompt-box" @submit.prevent="create">
      <label for="idea" class="sr-only">描述你想创建的网站</label
      ><textarea
        id="idea"
        v-model="prompt"
        maxlength="8000"
        placeholder="我想做一个……试着描述你的想法、风格和功能"
        @keydown.ctrl.enter.prevent="create"
      ></textarea>
      <div class="prompt-bottom">
        <span><Sparkles :size="15" /> {{ selectedExample ? '使用示例网站' : '智能匹配生成模式' }}</span
        ><button
          class="create-button"
          :disabled="busy || !prompt.trim()"
          aria-label="开始创建"
        >
          <LoaderCircle v-if="busy" class="spin" :size="20" /><ArrowUp
            v-else
            :size="22"
          />
        </button>
      </div>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="suggestions">
      <span>试试这些示例</span
      ><button
        v-for="(idea, i) in ideas"
        :key="idea.id"
        :class="{ selected: selectedExample?.id === idea.id }"
        :aria-pressed="selectedExample?.id === idea.id"
        :title="idea.description"
        @click="prompt = idea.prompt"
      >
        <component :is="[Globe, PanelsTopLeft, Layers][i % 3]" :size="14" />{{ idea.title }}
      </button>
    </div>
  </section>
  <section v-if="user" class="gallery section-width home-gallery">
    <div class="section-heading">
      <h2>我的作品</h2>
      <form class="search-bar" @submit.prevent="minePage = 1; loadMine()">
        <Search :size="18" />
        <input v-model="mineQuery" placeholder="搜索我的应用名称" aria-label="搜索我的作品" />
        <button class="button small secondary">搜索</button>
      </form>
    </div>
    <div v-if="mineLoading" class="empty">正在加载作品…</div>
    <div v-else-if="mine.length" class="card-grid">
      <AppCard v-for="(app, i) in mine" :key="app.id" :app="app" :index="i"
        @open="(a) => router.push(`/apps/${a.id}`)" @edit="edit" />
    </div>
    <p v-else class="muted">还没有匹配的作品，在上方写下想法开始创建。</p>
    <div v-if="mineTotal > pageSize" class="pagination">
      <button :disabled="mineLoading || minePage === 1" @click="minePage--; loadMine()">上一页</button>
      <span>{{ minePage }} / {{ Math.ceil(mineTotal / pageSize) }}</span>
      <button :disabled="mineLoading || minePage * pageSize >= mineTotal" @click="minePage++; loadMine()">下一页</button>
    </div>
  </section>
  <section class="gallery section-width home-gallery featured-gallery">
    <div class="section-heading">
      <h2>精选案例</h2>
      <form class="search-bar" @submit.prevent="featuredPage = 1; loadFeatured()">
        <Search :size="18" />
        <input v-model="featuredQuery" placeholder="搜索精选应用名称" aria-label="搜索精选案例" />
        <button class="button small secondary">搜索</button>
      </form>
    </div>
    <div v-if="loading" class="empty">正在加载作品…</div>
    <div v-else-if="featured.length" class="card-grid">
      <AppCard
        v-for="(app, i) in featured"
        :key="app.id"
        :app="app"
        :index="i"
        public-card
        @open="(a) => a.url && windowOpen(a.url)"
      />
    </div>
    <div v-else class="gallery-empty">
      <div class="empty-art"><PanelsTopLeft :size="30" /></div>
      <div>
        <h3>下一个好作品，可能就是你的</h3>
        <p>精选作品将在这里展示。写下你的第一个想法，开始创作吧。</p>
      </div>
      <button v-if="ideas.length" class="button secondary" @click="prompt = ideas[0].prompt">
        试试个人作品集 <ArrowRight :size="16" />
      </button>
    </div>
    <div v-if="featuredTotal > pageSize" class="pagination">
      <button
        :disabled="loading || featuredPage === 1"
        @click="
          featuredPage--;
          loadFeatured();
        "
      >
        上一页</button
      ><span>{{ featuredPage }} / {{ Math.ceil(featuredTotal / pageSize) }}</span
      ><button
        :disabled="loading || featuredPage * pageSize >= featuredTotal"
        @click="
          featuredPage++;
          loadFeatured();
        "
      >
        下一页
      </button>
    </div>
  </section>
  <div v-if="editing" class="modal-backdrop" @click.self="editing = null">
    <form class="modal" @submit.prevent="save">
      <div class="section-heading">
        <h2>编辑应用</h2>
        <button type="button" class="icon-button" @click="editing = null">
          <X :size="20" />
        </button>
      </div>
      <label>应用名称<input v-model="name" required maxlength="100" /></label>
      <div class="modal-actions">
        <button type="button" class="button danger" @click="remove">
          删除应用</button
        ><button class="button dark">保存修改</button>
      </div>
    </form>
  </div>
</template>
<script lang="ts">
function windowOpen(url: string) {
  window.open(url, "_blank", "noopener,noreferrer");
}
</script>
