import { HomeOutlined, EyeInvisibleOutlined, ReadOutlined } from '@ant-design/icons'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import ComponentDocPage from './pages/ComponentDoc.jsx'
import ApplyPage from './pages/ApplyPage.jsx'

/** 菜单 + 路由清单（lead 005 §8.3：主壳按本清单重组）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-mask 脱敏', short: 'z-mask' }

export const menuItems = [
    { key: '/z-mask/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-mask/apply', label: '脱敏执行', icon: <EyeInvisibleOutlined /> },
    { key: '/z-mask/docs', label: '组件文档', icon: <ReadOutlined /> },
]

export const routes = [
    { path: '/z-mask/home', Component: HomePage },
    { path: '/z-mask/apply', Component: ApplyPage },
    { path: '/z-mask/docs', Component: ComponentDocPage },
]

export { HomePage, LoginPage, ComponentDocPage }
