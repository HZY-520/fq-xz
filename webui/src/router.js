import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/search' },
  {
    path: '/search',
    name: 'search',
    component: () => import('./views/SearchView.vue'),
    meta: { title: '搜索书籍', sub: '搜索番茄小说并创建下载任务' },
  },
  {
    path: '/jobs',
    name: 'jobs',
    component: () => import('./views/JobsView.vue'),
    meta: { title: '下载任务', sub: '任务进度、取消与格式选择' },
  },
  {
    path: '/history',
    name: 'history',
    component: () => import('./views/HistoryView.vue'),
    meta: { title: '下载历史', sub: '已完成书籍的记录' },
  },
  {
    path: '/config',
    name: 'config',
    component: () => import('./views/ConfigView.vue'),
    meta: { title: '设置', sub: '输出格式、有声小说与完整配置项' },
  },
  {
    path: '/about',
    name: 'about',
    component: () => import('./views/AboutView.vue'),
    meta: { title: '关于', sub: '开发者信息与开源仓库' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/search' },
]

export default createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})