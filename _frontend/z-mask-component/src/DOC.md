# @yuku123/z-mask-component

z-mask（脱敏中间件）可复用前端组件（lead 005 §9 component 共享）。

## 使用

```js
import { configureMask } from '@yuku123/z-mask-component'
configureMask({ apiBase: '' })
```

## pages 入口（路由清单）

```js
import { routes, menuItems, appMeta } from '@yuku123/z-mask-component/pages'
```

| 路径 | 页面 | 说明 |
|---|---|---|
| `/z-mask/home` | HomePage | 首页着陆 |
| `/z-mask/apply` | ApplyPage | 脱敏执行试跑（POST `/z-team/api/mask/apply`） |
| `/z-mask/docs` | ComponentDocPage | 组件文档 |

## 后端契约

- `POST /z-team/api/mask/apply`，body `{text}`，返回 `{ok, masked, maskedText, count, items[]}`
- `GET /api/mask/health`（健康检查）

`.` 入口保持 props-only（lead 005 §5）；fetch 只出现在 `./pages` 的默认装配里。
