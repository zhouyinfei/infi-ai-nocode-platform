export interface User {
  id: string;
  userAccount: string;
  userName: string;
  userAvatar?: string;
  userProfile?: string;
  userRole: "user" | "admin";
  createTime?: string;
}
export type CodeType = "html" | "multi_file" | "vue_project";
export interface AppItem {
  id: string;
  appName: string;
  creatorName?: string;
  initPrompt: string;
  codeGenType: CodeType;
  cover?: string;
  deployKey?: string;
  priority: number;
  userId: string;
  createTime: string;
  url?: string;
}
export interface Page<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
}
export interface Message {
  id: string;
  message: string;
  messageType: "user" | "ai";
  createTime: string;
}
export interface Task {
  id: string;
  appId: string;
  state: "running" | "succeeded" | "failed";
  stage: string;
  message: string;
}
export const typeLabels: Record<CodeType, string> = {
  html: "原生 HTML",
  multi_file: "原生多文件",
  vue_project: "Vue 工程",
};
