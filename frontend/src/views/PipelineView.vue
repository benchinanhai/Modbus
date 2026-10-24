<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useLiveStore } from '../stores/live'
import { get } from '../api'

const store = useLiveStore()
const detail = ref(null)
const step = ref(0)
let timer = null

const group = computed(() => detail.value?.groups?.[0] || null)
const steps = computed(() => [
  {
    n: '01',
    t: '传感器',
    s: '从站 1 · 温度变送器',
    d: '寄存器 0x0000 保存 float32 温度，0x0001 是高字/低字的后半段。',
    f: group.value?.bytes || '41 F4 00 00',
    ms: '0.1',
    owner: '现场'
  },
  {
    n: '02',
    t: 'RS-485 总线',
    s: '9600 8N1 · 半双工',
    d: '主站发请求，总线上同一时刻只能有一帧。',
    f: group.value?.readRequest || '01 03 00 00 00 02 C4 0B',
    ms: '4.2',
    owner: '物理层'
  },
  {
    n: '03',
    t: '通道队列',
    s: '深度 2 · 写优先',
    d: '请求先入队。通道内严格串行，队列是唯一并发入口。',
    f: 'queue.wait() → 1.0 ms',
    ms: '1.0',
    owner: '网关'
  },
  {
    n: '04',
    t: 'IO 线程',
    s: '独占串口',
    d: '发帧、等响应；超时重试 3 次，仍失败就标坏点。',
    f: group.value?.readResponse || '01 03 04 41 F4 00 00 AF FD',
    ms: '6.5',
    owner: '网关'
  },
  {
    n: '05',
    t: '解码',
    s: 'float32 · ABCD',
    d: '4 个字节按字序拼回，再乘量纲；字序错就得到垃圾值。',
    f: group.value ? `${group.value.bytes} → ${group.value.value}` : '0x41F4 0x0000 → 30.5 °C',
    ms: '0.3',
    owner: '网关'
  },
  {
    n: '06',
    t: '快照缓存',
    s: '值 + 时间戳 + 质量',
    d: '覆盖写最新值。读接口永远读缓存，不阻塞在串口上。',
    f: '{ v: 30.5, q: "good", ts: "12:00:01" }',
    ms: '0.1',
    owner: '网关'
  },
  {
    n: '07',
    t: 'WebSocket',
    s: '变化才推送',
    d: '只有变化超过死区才推，避免前端被刷屏。',
    f: '{ dev: "T-01", point: "temp", v: 30.5 }',
    ms: '0.2',
    owner: '服务端'
  },
  {
    n: '08',
    t: '浏览器',
    s: 'Vue3 看板',
    d: '收到消息更新 Pinia，组件重绘；曲线和表格同时变化。',
    f: '<Sparkline :data="history" />',
    ms: '0.1',
    owner: '前端'
  }
])

function play() {
  if (timer) {
    clearInterval(timer)
    timer = null
    return
  }
  step.value = 0
  timer = setInterval(() => {
    step.value = (step.value + 1) % 8
  }, 950)
}

onMounted(async () => {
  const id = store.devices[0]?.id || 'T-01'
  detail.value = await get(`/devices/${id}`)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div>
        <h2>一次采集的完整链路</h2>
        <p>从传感器寄存器到浏览器像素，中间发生了什么</p>
      </div>
      <div style="display: flex; gap: 8px; flex-wrap: wrap">
        <router-link class="mp-btn" to="/threads">看并发线程</router-link>
        <button type="button" class="mp-btn primary" @click="play">{{ timer ? '停止' : '播放一次' }}</button>
      </div>
    </div>

    <div class="mp-panel">
      <div class="mp-pipe">
        <button
          v-for="(item, index) in steps"
          :key="item.n"
          type="button"
          class="mp-pnode"
          :class="{ on: step === index }"
          @click="step = index"
        >
          <i>STEP {{ item.n }}</i>
          <b>{{ item.t }}</b>
          <span>{{ item.s }}</span>
        </button>
      </div>
    </div>

    <div class="mp-split">
      <div class="mp-panel">
        <div class="mp-detail-h">
          <b>{{ steps[step].t }}</b
          ><span class="mp-lv info">{{ steps[step].s }}</span>
        </div>
        <div class="mp-note">{{ steps[step].d }}</div>
        <code class="mp-frame">{{ steps[step].f }}</code>
        <div class="mp-kv">
          <div>
            <span>耗时</span><b>{{ steps[step].ms }} ms</b>
          </div>
          <div>
            <span>责任方</span><b>{{ steps[step].owner }}</b>
          </div>
        </div>
        <div class="mp-band" style="margin-top: 12px">
          这条链路每秒重复 24 次；任何一步阻塞，都会先体现在队列深度上。
        </div>
      </div>

      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>一次轮询的时间预算</h3>
          <span>9600 bps 实测口径</span>
        </div>
        <div class="mp-load">
          <div class="mp-load-row">
            <span>排队</span>
            <div class="mp-bar"><i style="width: 8%"></i></div>
            <span>1.0 ms</span>
          </div>
          <div class="mp-load-row">
            <span>发帧</span>
            <div class="mp-bar"><i style="width: 34%"></i></div>
            <span>4.2 ms</span>
          </div>
          <div class="mp-load-row">
            <span>从站</span>
            <div class="mp-bar"><i style="width: 52%"></i></div>
            <span>6.5 ms</span>
          </div>
          <div class="mp-load-row">
            <span>收帧</span>
            <div class="mp-bar"><i style="width: 26%"></i></div>
            <span>3.2 ms</span>
          </div>
          <div class="mp-load-row">
            <span>解码</span>
            <div class="mp-bar"><i style="width: 3%"></i></div>
            <span>0.3 ms</span>
          </div>
          <div class="mp-load-row">
            <span>推送</span>
            <div class="mp-bar"><i style="width: 2%"></i></div>
            <span>0.2 ms</span>
          </div>
        </div>
        <div class="mp-kv">
          <div><span>单次合计</span><b>15.4 ms</b></div>
          <div><span>单通道上限</span><b>约 64 次 / 秒</b></div>
          <div><span>24 点 1 秒周期</span><b>约 37% 负载</b></div>
        </div>
        <div class="mp-note" style="margin-top: 10px">
          这就是“为什么不能多线程抢一个串口”的量化答案：总线时间是硬预算。
        </div>
      </div>
    </div>
  </div>
</template>
