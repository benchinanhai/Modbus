import { createRouter, createWebHistory } from 'vue-router'
import OverviewView from './views/OverviewView.vue'
import GatewaysView from './views/GatewaysView.vue'
import DevicesView from './views/DevicesView.vue'
import PipelineView from './views/PipelineView.vue'
import ThreadsView from './views/ThreadsView.vue'
import OnboardView from './views/OnboardView.vue'
import CommandsView from './views/CommandsView.vue'
import LogsView from './views/LogsView.vue'
import DocsView from './views/DocsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    { path: '/overview', component: OverviewView, meta: { title: '运行总览' } },
    { path: '/gateways', component: GatewaysView, meta: { title: '网关与通道' } },
    { path: '/devices/:id?', component: DevicesView, meta: { title: '设备与传感器' } },
    { path: '/pipeline', component: PipelineView, meta: { title: '采集链路' } },
    { path: '/threads', component: ThreadsView, meta: { title: '并发线程' } },
    { path: '/onboard', component: OnboardView, meta: { title: '设备接入' } },
    { path: '/commands', component: CommandsView, meta: { title: '指令下发' } },
    { path: '/logs', component: LogsView, meta: { title: '日志中心' } },
    { path: '/docs', component: DocsView, meta: { title: '功能说明' } },
    { path: '/plan', redirect: '/docs' }
  ]
})

export default router
