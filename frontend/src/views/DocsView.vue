<script setup>
import { ref } from 'vue'

const tab = ref('flow')
const tabs = [
  ['flow', '架构流程图'],
  ['features', '功能使用与实现']
]

/**
 * 功能清单：每项都给出“怎么用”（操作路径）与“怎么实现”（后端类与关键机制），
 * 让面试官既能照着操作，也能定位到代码。
 */
const features = [
  {
    key: 'onboard',
    title: '设备接入（TCP / RTU）',
    route: '/onboard',
    usage: [
      '选择 TCP 或 RTU，填写 IP/端口或串口/波特率、从站地址',
      '点“测试读取”：返回原始寄存器、uint16/int16、float32 四种字序、ASCII',
      '确认数值和字序后，填写名称与点表，点“接入并开始轮询”'
    ],
    impl: [
      'RealDeviceService：按端点创建网关与通道 worker，配置持久化到 config/real-devices.json',
      'ModbusTcpClient / ModbusRtuClient：FC03 试读，异常码与超时原样返回',
      '从站 Unit 冲突在接入时校验拒绝'
    ]
  },
  {
    key: 'pointtable',
    title: '自定义点表 + Excel 导入',
    route: '/onboard',
    usage: [
      '点表编辑器：增删改点位（名称/功能码/地址/类型/字序/缩放/单位/读写）',
      '下载模板 → 按列填写 → Excel 导入；坏行逐行提示、不阻断其他行'
    ],
    impl: [
      'PointConfig：类型（int16/uint16/int32/uint32/float32）、字序（32 位）、缩放、读写',
      'PointTableService（Apache POI）：表头校验、逐行解析，地址重叠与跨度在接入时拦截'
    ]
  },
  {
    key: 'poll',
    title: '实时采集与解码',
    route: '/overview',
    usage: [
      '接入后自动每秒轮询；总览/设备页实时刷新数值与质量',
      '设备页展示点表驱动的寄存器编组与真实原始字节'
    ],
    impl: [
      'ChannelRuntime：每通道一个 worker + PriorityBlockingQueue，通道内串行、通道间并行',
      '轮询按功能码分块：FC03 一块、FC04 一块，单块 ≤ 125 寄存器',
      'RegisterService.decodePoint：16 位读 1 个寄存器、32 位按字序读 2 个并做符号扩展'
    ]
  },
  {
    key: 'command',
    title: '指令下发（FC06 / FC16）',
    route: '/commands',
    usage: [
      '选择设备 → 从“可写点位”下拉选点（自动填功能码/地址/当前值）→ 下发',
      '时间线展示：入队 → 通道令牌 → 发送 → 从站 → 校验 → 更新缓存'
    ],
    impl: [
      '命令进入通道队列优先级 0（高于轮询），通道内与轮询严格串行',
      '真实设备：FC06/FC16 实际写从站 → 回读校验 → 同步原始寄存器缓存',
      '失败返回真实异常码；全过程写审计日志并经 WebSocket 推送'
    ]
  },
  {
    key: 'slave',
    title: '平台作为从站对外服务',
    route: '/onboard',
    usage: [
      'SCADA 等外部主站连接平台 1502 端口，Unit 见接入页从表面板',
      'FC03/FC04 读点位实时值；FC06/FC16 写可写点位——透传到真实设备并回读'
    ],
    impl: [
      'ModbusSlaveService：每连接一个线程，镜像由各设备点表生成',
      '只读点位写命令返回异常码 02；Unit 冲突时真实设备优先',
      '写成功同步原始寄存器缓存并推送遥测'
    ]
  },
  {
    key: 'longstring',
    title: '长字符串存储与解析',
    route: '/devices',
    usage: [
      '设备页按 7 步演示：编码 → 打包 → 分片 → 写控制区 → 写数据区 → 拼接 → 解码',
      '控制区（长度/片数/片号/状态/CRC）与数据区（0x0110 起）全部可见'
    ],
    impl: [
      '控制区固定 0x0100–0x0104，数据区 0x0110 起，每片最多 16 个寄存器（32 B）',
      '解析先读长度再按长度截断，CRC16 校验；填充的 00 不当作字符'
    ]
  },
  {
    key: 'observability',
    title: '实时推送、线程与日志',
    route: '/threads',
    usage: [
      '所有页面数据自动刷新；日志中心按级别/关键字过滤，点行看 trace',
      '线程页展示所有通道泳道，可切“错误示范”看线程抢同一串口'
    ],
    impl: [
      'TelemetrySocket：采集/日志/指令经 WebSocket /ws/telemetry 实时推送',
      'ThreadOp 记录通道内每次事务，泳道按序排列；模拟与真实通道隔离调度'
    ]
  }
]
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div>
        <h2>功能说明</h2>
        <p>平台架构流程图 + 每个功能的使用方式与实现位置</p>
      </div>
      <span class="mp-pill">Spring Boot 3 · Vue 3 · Modbus TCP/RTU</span>
    </div>

    <div class="mp-planbar">
      <button
        v-for="item in tabs"
        :key="item[0]"
        type="button"
        :class="{ on: tab === item[0] }"
        @click="tab = item[0]"
      >
        {{ item[1] }}
      </button>
    </div>

    <template v-if="tab === 'flow'">
      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>平台架构流程图</h3>
          <span>三条链路：① 采集上行 · ② 指令下行 · ③ 从站对外</span>
        </div>
        <div class="mp-flow-wrap">
          <svg class="mp-archflow" viewBox="0 0 980 410" role="img" aria-label="平台架构流程图">
            <defs>
              <marker id="mp-arr-a" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                <path d="M0,0 L8,4 L0,8 z" class="arr-a"></path>
              </marker>
              <marker id="mp-arr-b" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                <path d="M0,0 L8,4 L0,8 z" class="arr-b"></path>
              </marker>
              <marker id="mp-arr-c" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                <path d="M0,0 L8,4 L0,8 z" class="arr-c"></path>
              </marker>
            </defs>

            <!-- 链路标题 -->
            <text x="12" y="24" class="lane lane-a">① 采集上行：设备 → 控制台（每秒轮询）</text>
            <text x="12" y="150" class="lane lane-b">② 指令下行：控制台 → 设备（FC06/FC16 + 回读）</text>
            <text x="12" y="276" class="lane lane-c">③ 从站对外：SCADA → 平台 → 设备（:1502）</text>

            <!-- ① 采集上行 -->
            <g>
              <rect x="10" y="40" width="140" height="62" rx="8" class="node node-a"></rect>
              <text x="20" y="62" class="t">现场设备</text>
              <text x="20" y="78" class="s">真实 TCP / RTU</text>
              <text x="20" y="92" class="s">模拟设备 × 24</text>

              <rect x="175" y="40" width="140" height="62" rx="8" class="node node-a"></rect>
              <text x="185" y="62" class="t">通道 worker × N</text>
              <text x="185" y="78" class="s">优先队列 写0/轮询1</text>
              <text x="185" y="92" class="s">通道内串行</text>

              <rect x="340" y="40" width="140" height="62" rx="8" class="node node-a"></rect>
              <text x="350" y="62" class="t">点表解码</text>
              <text x="350" y="78" class="s">FC03/04 分块读</text>
              <text x="350" y="92" class="s">类型 · 字序 · 缩放</text>

              <rect x="505" y="40" width="140" height="62" rx="8" class="node node-a"></rect>
              <text x="515" y="62" class="t">状态缓存</text>
              <text x="515" y="78" class="s">值 · 质量 · 历史</text>
              <text x="515" y="92" class="s">原始寄存器</text>

              <rect x="670" y="40" width="140" height="62" rx="8" class="node node-a"></rect>
              <text x="680" y="62" class="t">REST + WebSocket</text>
              <text x="680" y="78" class="s">/api/** · /ws/telemetry</text>

              <rect x="835" y="40" width="140" height="62" rx="8" class="node node-a"></rect>
              <text x="845" y="62" class="t">Vue3 控制台</text>
              <text x="845" y="78" class="s">总览 · 设备 · 线程</text>
              <text x="845" y="92" class="s">日志 · 指令</text>

              <line x1="150" y1="71" x2="172" y2="71" class="flow flow-a"></line>
              <line x1="315" y1="71" x2="337" y2="71" class="flow flow-a"></line>
              <line x1="480" y1="71" x2="502" y2="71" class="flow flow-a"></line>
              <line x1="645" y1="71" x2="667" y2="71" class="flow flow-a"></line>
              <line x1="810" y1="71" x2="832" y2="71" class="flow flow-a"></line>
            </g>

            <!-- ② 指令下行 -->
            <g>
              <rect x="175" y="166" width="140" height="62" rx="8" class="node node-b"></rect>
              <text x="185" y="188" class="t">指令页 /commands</text>
              <text x="185" y="204" class="s">可写点位下拉</text>
              <text x="185" y="218" class="s">自动填地址/值</text>

              <rect x="340" y="166" width="140" height="62" rx="8" class="node node-b"></rect>
              <text x="350" y="188" class="t">CommandService</text>
              <text x="350" y="204" class="s">参数校验 · 生成帧</text>
              <text x="350" y="218" class="s">FC06 / FC16</text>

              <rect x="505" y="166" width="140" height="62" rx="8" class="node node-b"></rect>
              <text x="515" y="188" class="t">通道队列</text>
              <text x="515" y="204" class="s">优先级 0 高于轮询</text>
              <text x="515" y="218" class="s">与轮询串行</text>

              <rect x="670" y="166" width="140" height="62" rx="8" class="node node-b"></rect>
              <text x="680" y="188" class="t">设备 FC06/FC16</text>
              <text x="680" y="204" class="s">实际写从站</text>
              <text x="680" y="218" class="s">异常码原样返回</text>

              <rect x="835" y="166" width="140" height="62" rx="8" class="node node-b"></rect>
              <text x="845" y="188" class="t">回读校验 + 审计</text>
              <text x="845" y="204" class="s">一致才返回成功</text>
              <text x="845" y="218" class="s">trace 写入日志</text>

              <line x1="315" y1="197" x2="337" y2="197" class="flow flow-b"></line>
              <line x1="480" y1="197" x2="502" y2="197" class="flow flow-b"></line>
              <line x1="645" y1="197" x2="667" y2="197" class="flow flow-b"></line>
              <line x1="810" y1="197" x2="832" y2="197" class="flow flow-b"></line>
              <!-- 回到设备：闭环 -->
              <path d="M 740 228 C 600 268, 260 240, 90 110" class="flow flow-b dashed"></path>
              <text x="330" y="252" class="s">写命令最终落到左侧设备</text>
            </g>

            <!-- ③ 从站对外 -->
            <g>
              <rect x="835" y="292" width="140" height="62" rx="8" class="node node-c"></rect>
              <text x="845" y="314" class="t">SCADA 主站</text>
              <text x="845" y="330" class="s">外部 Modbus 主站</text>
              <text x="845" y="344" class="s">按 Unit 读写</text>

              <rect x="670" y="292" width="140" height="62" rx="8" class="node node-c"></rect>
              <text x="680" y="314" class="t">从站 :1502</text>
              <text x="680" y="330" class="s">Modbus TCP Server</text>

              <rect x="505" y="292" width="140" height="62" rx="8" class="node node-c"></rect>
              <text x="515" y="314" class="t">点表镜像</text>
              <text x="515" y="330" class="s">Unit = slaveUnitId</text>
              <text x="515" y="344" class="s">地址 = 点表地址</text>

              <rect x="340" y="292" width="140" height="62" rx="8" class="node node-c"></rect>
              <text x="350" y="314" class="t">读当前值 / 写透传</text>
              <text x="350" y="330" class="s">FC03/04 读镜像</text>
              <text x="350" y="344" class="s">FC06/16 转发设备</text>

              <line x1="835" y1="323" x2="813" y2="323" class="flow flow-c"></line>
              <line x1="670" y1="323" x2="648" y2="323" class="flow flow-c"></line>
              <line x1="505" y1="323" x2="483" y2="323" class="flow flow-c"></line>
              <!-- 回到设备：闭环 -->
              <path d="M 340 323 C 260 323, 180 300, 120 190" class="flow flow-c dashed"></path>
              <text x="150" y="300" class="s">可写点位透传到设备</text>
            </g>

            <!-- 底部关键约束 -->
            <g>
              <rect x="10" y="376" width="300" height="26" rx="13" class="chipbox"></rect>
              <text x="25" y="393" class="s">通道内串行 · 通道间并行</text>
              <rect x="330" y="376" width="300" height="26" rx="13" class="chipbox"></rect>
              <text x="345" y="393" class="s">count=N 一次读回 · 响应 2N 字节</text>
              <rect x="650" y="376" width="325" height="26" rx="13" class="chipbox"></rect>
              <text x="665" y="393" class="s">真实写入必须回读一致才算成功</text>
            </g>
          </svg>
        </div>
        <div class="mp-note" style="margin-top: 8px">
          三条链路共享同一点表与状态缓存：采集更新值，指令与从站写改值后立即反映到所有页面。
        </div>
      </div>
    </template>

    <template v-else>
      <section v-for="feature in features" :key="feature.key" class="mp-panel">
        <div class="mp-panel-h">
          <h3>{{ feature.title }}</h3>
          <span class="mp-lv info">{{ feature.route }}</span>
        </div>
        <div class="mp-regd">
          <div>
            <span class="mp-lv info">怎么用</span>
            <ul class="mp-checklist">
              <li v-for="item in feature.usage" :key="item">{{ item }}</li>
            </ul>
          </div>
          <div>
            <span class="mp-lv info">怎么实现</span>
            <ul class="mp-checklist">
              <li v-for="item in feature.impl" :key="item">
                <code>{{ item.split('：')[0] }}</code
                >{{ item.includes('：') ? '：' + item.split('：').slice(1).join('：') : item }}
              </li>
            </ul>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>
