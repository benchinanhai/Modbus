<script setup>
import { computed, ref } from 'vue'
import { useLiveStore } from '../stores/live'

const store = useLiveStore()
const selected = ref('GW-01')

const gateways = computed(() => store.gateways)
const gateway = computed(() => store.gatewayMap[selected.value] || gateways.value[0])
const lane = computed(() => store.lanes.find((item) => item.gatewayId === selected.value))
const ops = computed(() => (lane.value?.ops || []).slice(-10).reverse())
const maxDuration = computed(() => Math.max(20, ...ops.value.map((op) => op.durationMs)))
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div>
        <h2>网关与通道</h2>
        <p>网关是协议边界：向下管总线，向上把寄存器翻译成点位</p>
      </div>
      <router-link class="mp-btn" to="/threads">查看并发线程</router-link>
    </div>

    <div class="mp-grid-three">
      <button
        v-for="item in gateways"
        :key="item.id"
        type="button"
        class="mp-panel"
        style="text-align: left"
        :style="{ borderColor: item.id === selected ? 'var(--accent)' : '' }"
        @click="selected = item.id"
      >
        <div class="mp-panel-h">
          <h3>{{ item.id }} {{ item.name }}</h3>
          <span>
            <span class="mp-lv" :class="item.real ? 'warn' : 'info'">{{ item.real ? '真实' : '模拟' }}</span>
            <span class="mp-lv info" style="margin-left: 4px">{{ item.transport }}</span>
          </span>
        </div>
        <div class="mp-note">{{ item.endpoint }}</div>
        <div class="mp-kv">
          <div>
            <span>下挂设备</span><b>{{ item.deviceCount }} 台</b>
          </div>
          <div>
            <span>采集周期</span><b>{{ item.cycleMs }} ms</b>
          </div>
          <div>
            <span>队列深度</span><b>{{ item.queueDepth }}</b>
          </div>
          <div>
            <span>总线利用率</span><b>{{ item.utilization }}%</b>
          </div>
          <div>
            <span>IO 线程</span><b>{{ item.threadName }}</b>
          </div>
        </div>
      </button>
    </div>

    <div class="mp-split">
      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>{{ gateway?.id }} 通道队列</h3>
          <span>写优先，轮询让路</span>
        </div>
        <div class="mp-stats">
          <span
            >线程 <b>{{ gateway?.threadName }}</b></span
          >
          <span
            >状态 <b>{{ lane?.state || 'WAITING' }}</b></span
          >
          <span
            >队列 <b>{{ gateway?.queueDepth ?? 0 }}</b></span
          >
        </div>
        <div class="mp-load">
          <div class="mp-load-row">
            <span>队列深度</span>
            <div class="mp-bar">
              <i :style="{ width: Math.min(100, (gateway?.queueDepth || 0) * 8) + '%' }"></i>
            </div>
            <span>{{ gateway?.queueDepth || 0 }} 个任务</span>
          </div>
          <div class="mp-load-row">
            <span>总线利用</span>
            <div class="mp-bar"><i :style="{ width: (gateway?.utilization || 0) + '%' }"></i></div>
            <span>{{ gateway?.utilization || 0 }}%</span>
          </div>
        </div>
        <div class="mp-band" style="margin-top: 12px">
          通道内串行是物理约束，不是实现选择。队列是唯一的并发入口。
        </div>
        <div class="mp-note" style="margin-top: 10px">
          写命令优先级 0，轮询优先级 1；同一时刻只有一帧在总线上。
        </div>
      </div>

      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>最近事务</h3>
          <span>请求 → 响应</span>
        </div>
        <div class="mp-load">
          <div v-for="op in ops" :key="op.id" class="mp-load-row">
            <span>{{ op.deviceId }}</span>
            <div class="mp-bar">
              <i :style="{ width: Math.max(8, (op.durationMs / maxDuration) * 100) + '%' }"></i>
            </div>
            <span>{{ op.durationMs.toFixed(1) }} ms</span>
          </div>
          <div v-if="!ops.length" class="mp-note">等待采集数据…</div>
        </div>
        <div class="mp-kv">
          <div>
            <span>从站地址范围</span><b>1 – {{ gateway?.deviceCount || 0 }}</b>
          </div>
          <div><span>帧间隔</span><b>≥ 3.5 字符</b></div>
          <div><span>失败策略</span><b>重试 3 次 → 坏点</b></div>
        </div>
      </div>
    </div>
  </div>
</template>
