<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useLiveStore } from './stores/live'

const route = useRoute()
const store = useLiveStore()
const now = ref(new Date())
let refreshTimer
let clockTimer

const title = computed(() => route.meta.title || '运行总览')
const clock = computed(() => {
  const date = now.value
  const pad = (value) => String(value).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
})

onMounted(async () => {
  await store.init()
  refreshTimer = setInterval(() => store.refresh(), 5000)
  clockTimer = setInterval(() => {
    now.value = new Date()
  }, 1000)
})

onUnmounted(() => {
  clearInterval(refreshTimer)
  clearInterval(clockTimer)
  store.disconnect()
})
</script>

<template>
  <div class="mp-app">
    <aside class="mp-side">
      <div class="mp-brand">
        <div class="mp-logo">MB</div>
        <div>
          <b>Modbus 接入平台</b>
          <span>Gateway Console</span>
        </div>
      </div>

      <nav class="mp-nav">
        <router-link to="/overview">运行总览</router-link>
        <router-link to="/gateways">网关与通道</router-link>
        <router-link to="/devices">设备与传感器</router-link>
        <router-link to="/pipeline">采集链路</router-link>
        <router-link to="/threads">并发线程</router-link>
        <router-link to="/onboard">设备接入</router-link>
        <router-link to="/commands">指令下发</router-link>
        <router-link to="/logs">日志中心</router-link>
        <router-link to="/docs">功能说明</router-link>
      </nav>

      <div class="mp-side-foot">
        <button class="mp-btn" type="button" @click="store.togglePause()">
          {{ store.paused ? '继续采集' : '暂停采集' }}
        </button>
        <span class="mp-clock">{{ clock }}</span>
        <span class="mp-side-note"> Spring Boot 3 + Vue3。数据来自后端通道运行时，WebSocket 实时推送。 </span>
      </div>
    </aside>

    <div class="mp-work">
      <header class="mp-top">
        <div class="mp-crumb">
          <b>{{ title }}</b> · 一号车间 / 全部设备
        </div>
        <div class="mp-top-r">
          <span class="mp-pill" :class="{ ok: store.connected }">
            <span class="mp-dot" :class="{ warn: !store.connected }"></span>
            {{ store.connected ? '运行中' : '重连中' }}
          </span>
          <span class="mp-pill">{{ store.stats.throughput }} 点/秒</span>
          <span class="mp-pill">{{ store.gateways.length }} 网关 · {{ store.devices.length }} 设备</span>
        </div>
      </header>

      <main class="mp-body">
        <router-view />
      </main>
    </div>
  </div>
</template>
