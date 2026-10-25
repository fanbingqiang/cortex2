import { createRouter, createWebHashHistory } from 'vue-router'
const routes = [
  { path: '/', redirect: '/practice' },
  { path: '/practice', name: 'Practice', component: () => import('@/views/PracticeView.vue') },
  { path: '/cards', name: 'Cards', component: () => import('@/views/CardsView.vue') },
  { path: '/community', name: 'Community', component: () => import('@/views/CommunityView.vue') },
  { path: '/progress', name: 'Progress', component: () => import('@/views/ProfileView.vue') },
  { path: '/reports', name: 'Reports', component: () => import('@/views/ProfileView.vue') },
  { path: '/admin', name: 'Admin', component: () => import('@/views/AdminView.vue'), meta: { requireAdmin: true } },
]
const router = createRouter({ history: createWebHashHistory('/lab/'), routes })

// 路由守卫：非管理员禁止访问 /admin
router.beforeEach((to, _from, next) => {
  if (to.meta.requireAdmin) {
    const role = localStorage.getItem('role') || ''
    if (role === 'ROOT' || role === 'ADMIN') {
      next()
    } else {
      next('/practice')
    }
  } else {
    next()
  }
})

export default router
