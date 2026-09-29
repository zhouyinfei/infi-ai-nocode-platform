# 主域名 HTTPS 短路径部署

发布地址：`https://coze.ziyuanzz.online/9alNDz/`。
预览地址：`https://coze.ziyuanzz.online/preview/<临时票据>/index.html`。
两者复用主站 443 和现有证书，由 Nginx 代理至内部 `127.0.0.1:8125`。

## 部署步骤

1. 构建并部署本次后端和前端。后端启用 prod，配置
   `nocode.preview-base-url: https://coze.ziyuanzz.online`，
   `nocode.allowed-origin: https://coze.ziyuanzz.online`。
   如启动参数或环境变量覆盖了配置，也要移除地址中的 `:8124`。
   本地 application-prod.yml 已更新，该文件被 Git 忽略，服务器须同步修改。
2. 上传 `nocode-prod.conf`，例如 `/www/server/nginx/conf/nocode-prod.conf`。
3. 在宝塔该网站现有的 HTTPS **server 块内部**加入
   `include /www/server/nginx/conf/nocode-prod.conf;`。
   必须放在其他匹配 js/css/图片的正则 location 之前，避免作品资源被当成主站文件。
   不要放在 http 块，不需要新增 server、证书或公网端口。
   保留现有 `/` 的 SPA 回退以及 `/api/`、`/builder-api/` 代理。
   若已有 `location ^~ /`，移除该处的 `^~`，让作品路径能够匹配正则规则。
4. 执行 `nginx -t`，成功后 `nginx -s reload`；重启更新后的后端。
5. 工作台刷新页面和预览，已发布作品继续使用原有短码，无需重新生成或发布。
   未发布的作品先点击“发布网站”。旧的带 8124 端口收藏链接不会自动更新。

本配置替代上一次的生产 8124 监听配置；开发用 `nocode.conf` 保持不变。
公网只需现有 HTTPS 443；8125 保持仅监听回环地址。

## 页面隔离与兼容性

同域时，后端 CSP 与前端 iframe 均使用不带 `allow-same-origin` 的 sandbox，
生成脚本不能读取主站 DOM、Cookie 或 localStorage。新窗口中的作品也受后端 CSP 限制。
元素编辑桥接通过具体 iframe 的窗口来源校验，支持沙箱的 opaque origin。
后端为产物开启不携带凭据的 CORS，支持沙箱中的本地 JS 模块和字体。
生成页面依赖 localStorage、Cookie 的功能在该模式下不可用；内存中的普通交互仍可运行。
部署时应一起更新前后端，且不要隐藏后端的 Content-Security-Policy 响应头。

## 验证

### 封面图片 404

`/api/covers/*.png` 必须代理到业务后端 `8765`，不能交给主站图片缓存规则或作品服务 `8125`。
本配置使用 `location ^~ /api/covers/`，避免宝塔等配置中的图片正则 location 截获请求。
更新 include 文件后执行 `nginx -t`，通过后再执行 `nginx -s reload`。
若已有同名 location，应替换原规则，避免重复定义。

在服务器上将下面的文件名换成浏览器中失败请求的真实文件名，分别检查：

```sh
curl -I http://127.0.0.1:8765/api/covers/1-3198ef7d-c14a-4f62-997a-9dbd43d375e9.png
curl -I https://coze.ziyuanzz.online/api/covers/1-3198ef7d-c14a-4f62-997a-9dbd43d375e9.png
```

后端返回 200、域名返回 404 表示反向代理配置问题。两者都应返回 200 和 `Content-Type: image/png`。
若后端也返回 404，检查 `nocode.screenshot-dir` 中是否存在该文件；默认 `./tmp/screenshots`
相对于后端进程的工作目录。迁移部署时须保留截图目录，或设置固定的持久化绝对路径。
文件已丢失时需要重新发布应用生成封面，仅修改 Nginx 无法恢复丢失的图片。

### 作品路由

- `curl -I http://127.0.0.1:8125/` 返回 404 表示内部服务可达，根路径没有作品。
- 在工作台获取新的预览 URL，确认返回 200、生成 HTML、CSP sandbox。
- 用真实已发布短码验证 `/9alNDz` 重定向至 `/9alNDz/`，相对 CSS/JS 也返回 200。
- `/assets/` 仍加载主站文件，`/api/` 仍访问业务后端。
- 502 表示内部 8125 服务不可达；404 检查票据或发布短码是否有效。
- 若返回的是主站 HTML，检查 location 顺序与 SPA 回退；若只有内嵌失败，
  检查额外的 X-Frame-Options/CSP 限制。不要公开分享带票据的预览 URL。
