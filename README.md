# Modbus 接入与运维平台

一个能接真实设备、也能对外提供服务的 Modbus 采集网关与运维控制台：

- **主站**：接入真实 TCP/RTU 设备，自定义点表（支持 Excel 导入），每秒轮询、解码、展示。
- **从站**：平台自身暴露 Modbus TCP 服务，外部主站（SCADA）按 Unit 读写平台数据，可写点位的命令会透传到设备并回读校验。
- **可视化**：设备、寄存器、线程、日志、指令全部变成可点击的界面。

平台同时内置 3 个网关、24 台模拟设备（96 个点位），开箱即用；真实设备通过「设备接入」页添加。

## 技术栈

| 层 | 选择 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.3 · Spring Web / WebSocket · Apache POI（Excel）· jSerialComm（RTU） |
| 前端 | Vue 3.5 · Vite 6 · Pinia · Vue Router · 轻量 SVG 图表 |
| 协议 | Modbus TCP 客户端 + 从站（JDK Socket）· Modbus RTU 客户端 · CRC16 |
| 构建 | Maven 3.9 · npm · Docker 多阶段构建 |

## 快速启动

项目内工具链在 `env.sh`（Corretto JDK 21、Maven 3.9.9、Node 22）；本机已装可跳过。

### 开发模式（前后端分离）

```bash
cd /home/xujin/gateway
source env.sh

# 终端 1：后端 8080
./scripts/start-backend.sh

# 终端 2：前端 5173，Vite 代理 /api 与 /ws
./scripts/start-frontend.sh
```

打开 http://localhost:5173 。

### 单端口生产包（前端打进 jar）

```bash
./scripts/start-prod.sh
# http://localhost:8080
```


镜像内前端与后端同源，开放两个端口：

```text
8080/tcp  ->  Web 控制台 + REST + WebSocket
1502/tcp  ->  Modbus TCP 从站（对外服务）
```

配置目录通过 volume 挂载：`./config -> /app/config/real-devices.json`。

真实设备在宿主机局域网/宿主机本身时，compose 已配置
`host.docker.internal -> 宿主机`，接入页的 TCP 地址填 `host.docker.internal` 即可。

RTU 需要把串口映射进容器，取消 `docker-compose.yml` 里 `devices` 的注释：

```yaml
devices:
  - "/dev/ttyUSB0:/dev/ttyUSB0"
```

### 把镜像打包

```bash
docker save modbus-gateway:0.2.0 | gzip > modbus-gateway-0.2.0.tar.gz
```

本地部署：

```bash
docker load -i modbus-gateway-0.2.0.tar.gz
docker run -d --name modbus-gateway -p 8080:8080 -p 1502:1502 \
  -v "$PWD/config:/app/config" modbus-gateway:0.2.0
# 打开 http://localhost:8080
```

> 本机已验证 Docker 环境与构建配置（compose 校验通过、三个基础镜像标签存在）；
> 用户不在 docker 组时需要先执行上面的 usermod 命令。
> 单端口 jar 是与镜像完全等价的部署方式，已实测。

## 页面

| 路由 | 内容 |
|---|---|
| `/overview` | 在线设备、点位、成功率、P95、拓扑、温度曲线、事件、通道负载 |
| `/gateways` | 网关与通道：队列深度、总线利用率、最近事务耗时 |
| `/devices` | 设备与传感器：点表驱动的寄存器编组、多寄存器读取、长字符串存储/解析 |
| `/pipeline` | 一次采集的 8 步链路、请求/响应帧、时间预算 |
| `/threads` | 所有通道 IO 泳道；可切“错误示范”看线程抢同一串口 |
| `/onboard` | **设备接入**：测试读取、点表编辑器、Excel 导入、从站服务面板 |
| `/commands` | 写单个/多个寄存器；真实设备实际下发并回读，模拟设备可试超时/异常码 |
| `/logs` | 实时日志、级别过滤、搜索、trace 详情 |
| `/docs` | 功能说明：架构流程图、每个功能的使用方式与实现 |

## 真实设备接入

### 1. 测试读取

在 `/onboard` 选择 TCP 或 RTU，填写链路参数，点击“测试读取”。后端执行一次真实的 FC03，返回：

- 原始寄存器（十六进制 / uint16）
- int16 解释
- float32 的 ABCD / CDAB / BADC / DCBA 四种解释
- ASCII 预览

这一步用于确认链路和字节序，不落库。

### 2. 自定义点表

点表编辑器支持：

- 手工添加/删除/修改点位：名称、功能码（FC03/FC04）、地址、类型（int16/uint16/int32/uint32/float32）、字序（32 位）、缩放、单位、读写。
- **Excel 导入**：下载模板 → 按列填写 → 上传解析；解析失败的行逐行提示，不影响其他行。
- 点表在接入时随设备持久化，也可以通过 `POST /api/real-devices/{id}/points` 替换。

默认点表：

| 字段 | 地址 | 类型 |
|---|---|---|
| 温度 | `0x0000` | float32 |
| 湿度 | `0x0002` | int16 × 0.1 |
| 电流 | `0x0003` | int16 × 0.1 |
| 设定值 | `0x0008` | uint16 × 0.1（可写） |
| 报警下限 | `0x0009` | uint16 × 0.1（可写） |
| 报警上限 | `0x000A` | uint16 × 0.1（可写） |

轮询时按功能码分组成块读取（FC03 一块、FC04 一块），单块不超过 125 个寄存器；点表地址重叠会在接入时被拒绝。

### 3. 下发指令

在 `/commands` 选择真实设备，从“可写点位”下拉选择点位（自动填功能码/地址/当前值），下发：

- 后端把命令投递到该设备所属通道（优先级 0，高于轮询），实际执行 FC06/FC16；
- 写入后立即回读校验，一致才返回成功；
- 失败返回真实异常码（如 `Modbus exception code 2`）。

### 4. 平台作为从站对外提供服务

平台在 1502 端口（可配置）暴露 Modbus TCP 从站：

- **Unit ID** = 设备的“平台从站 Unit ID”（接入时可配置，冲突会被拒绝）；
- **寄存器地址** = 点表地址；FC03/FC04 读到的是点位的当前值；
- **FC06/FC16 写可写点位**会透传到真实设备并回读校验，模拟设备直接更新平台值；
- 只读点位收到写命令会返回异常码 02。

SCADA 等外部主站只需把平台当作一台普通的 Modbus TCP 设备。

## 后端结构

```
backend/src/main/java/com/example/gateway/
├── model/       DeviceState, GatewayState, PointConfig, RealDeviceConfig, Dtos, LogEntry, ThreadOp
├── store/       GatewayStore：种子数据、状态、历史、日志、线程事务
├── service/
│   ├── ChannelRuntime     每通道一个 worker + 优先队列（写 0 / 轮询 1）
│   ├── SimulationService  模拟设备调度；跳过真实网关
│   ├── RealDeviceService  真实设备配置、轮询、读写、点表替换、持久化
│   ├── ModbusTcpClient    FC03/04/06/16，MBAP + 事务 ID
│   ├── ModbusRtuClient    FC03/04/06/16，CRC16 + jSerialComm
│   ├── RegisterService    点表驱动的寄存器编组、TCP/RTU 帧、编解码、长字符串
│   ├── PointTableService  点表 Excel 解析与模板（Apache POI）
│   ├── ModbusSlaveService 平台作为 Modbus TCP 从站对外服务
│   └── CommandService     真实/模拟指令、时间线、审计
├── web/         ApiController, SpaController（SPA 回退）
├── ws/          TelemetrySocket：遥测 / 日志 / 指令推送
└── config/      CORS、WebSocket
```

### 关键设计

- **点表驱动**：轮询报文、寄存器编组、从站镜像、设备页全部由点表生成；改点表不改代码。
- **一条通道一个 worker**：`ChannelRuntime` 用 `PriorityBlockingQueue`，写命令优先级 0，轮询 1；同级按入队顺序 FIFO。通道内严格串行，通道间并行。
- **模拟与真实隔离**：`SimulationService` 只调度 `gateway.real == false` 的网关；真实网关由 `RealDeviceService` 自己调度，避免同一设备被采集两次。
- **TCP/RTU 帧分离**：TCP 生成 MBAP（无 CRC），RTU 生成“地址 + PDU + CRC16”。
- **真实写入回读校验**：FC06/FC16 执行后立即读回，一致才返回成功；写成功后同步原始寄存器缓存，从站镜像和设备页立刻反映新值。
- **从站 Unit 冲突校验**：两台设备不能用同一个 Unit ID，接入时拒绝。
- **原始寄存器**：真实设备详情展示最近一次读到的真实寄存器，而不是解码后的重建值。
- **同源部署**：Docker 镜像内前端静态资源由 Spring Boot 提供，`SpaController` 处理前端路由回退。

## API

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| GET | `/api/overview` | 总览指标、网关、事件 |
| GET | `/api/gateways` | 网关与设备 |
| GET | `/api/devices` | 设备列表（模拟 + 真实） |
| GET | `/api/devices/{id}` | 设备详情：点表、寄存器编组、帧、长字符串 |
| GET | `/api/devices/{id}/history` | 最近 60 个采样点 |
| GET | `/api/threads` | 所有通道泳道与最近事务 |
| GET | `/api/logs?level=&q=` | 日志查询 |
| POST | `/api/commands/preview` | 只生成帧，不下发 |
| POST | `/api/commands` | 下发写命令（真实设备会实际写） |
| GET | `/api/commands` | 最近命令 |
| GET | `/api/real-devices` | 真实设备配置与运行状态 |
| POST | `/api/real-devices/test` | 一次性读取，验证链路与字序 |
| POST | `/api/real-devices` | 接入真实设备并持久化 |
| DELETE | `/api/real-devices/{id}` | 移除真实设备 |
| POST | `/api/real-devices/{id}/points` | 替换真实设备点表 |
| POST | `/api/points/parse` | 解析上传的点表 xlsx |
| GET | `/api/points/template` | 下载点表模板 xlsx |
| GET | `/api/slave` | 从站状态与 Unit 映射 |
| GET | `/api/modbus/read` | 直连 TCP 从站读保持寄存器 |
| WS | `/ws/telemetry` | 遥测、日志、指令 |

## 联通云部署（HexHub 直连服务器）

目标：在联通云服务器上直接部署，防火墙映射后外网通过 **9527** 端口访问控制台，
1502 端口对外提供 Modbus TCP 从站。

### 1. HexHub 连接服务器

HexHub 安装在联通云服务器上（默认 9876 端口），浏览器打开 HexHub 界面，
添加/选择这台联通云服务器，即获得终端、文件管理与 Docker 可视化能力。

### 2. 服务器上拉取代码（HexHub 终端）

```bash
# 服务器没有 git 时先安装
apt update && apt install -y git
git clone https://github.com/benchinanhai/Modbus.git /opt/modbus-src
cd /opt/modbus-src
```

### 3. 构建镜像并启动（HexHub 终端）

```bash
docker compose -f docker-compose.cloud.yml up --build -d
```

云上构建已内置国内加速：npm 走 npmmirror、Maven 走阿里云镜像。
基础镜像（node/maven/eclipse-temurin）从 Docker Hub 拉取，慢的话在服务器的
`/etc/docker/daemon.json` 配置镜像加速后 `systemctl restart docker`。

### 4. 防火墙 / 安全组

联通云控制台安全组（或防火墙）放行：

| 端口 | 协议 | 用途 | 建议 |
|---|---|---|---|
| 9527 | TCP | Web 控制台（外网访问） | 必须放行 |
| 1502 | TCP | Modbus TCP 从站 | 仅在需要外网读写时放行，建议源 IP 白名单 |

服务器系统防火墙（ufw/firewalld）同步放行；22 端口维持原状。

### 5. 验证

浏览器打开 `http://服务器公网IP:9527`；从外部用 Modbus 主站连
`服务器公网IP:1502`，Unit 见「设备接入」页从表面板。

### 备选：本机构建镜像 tar 上传

不习惯在服务器上构建时，可在本地构建并导出，HexHub 文件管理上传后 `docker load`：

```bash
sudo docker build -t modbus-gateway:0.2.0 .
sudo docker save modbus-gateway:0.2.0 | gzip > modbus-gateway-0.2.0.tar.gz
# 服务器上：docker load -i modbus-gateway-0.2.0.tar.gz 后按第 3 步启动（免构建）
```

### 安全提示

- 1502 从站无鉴权，公网开放时务必配置源 IP 白名单，或仅在内网/VPN 内使用。
- 9527 建议后续加 Nginx 反向代理 + HTTPS + 认证。
- 接入真实设备时，在「设备接入」页填设备在联通云内网/专线可达的地址。

## 编码规范

- 后端遵循阿里巴巴 Java 开发手册：类/方法 Javadoc、常量替代魔法值、显式 `ScheduledThreadPoolExecutor`、线程命名、并发集合。
- 前端使用 ESLint 9 + Prettier，`npm run lint` / `npm run format`；组件使用 Vue3 `<script setup>`，复杂逻辑有中文注释。
- 协议相关代码（帧结构、CRC、字序、长字符串、从站写透传）都有注释说明约束。

## 已验证

- `mvn -DskipTests package` 通过；`npm run build`、`npm run lint` 通过。
- 真实 TCP：Python Modbus TCP 从站，测试读取 → 接入 → 轮询 30.5 °C / 55.0 % → FC06 写设定值 → FC16 写报警 → 读取回读一致 → 异常地址返回 `Modbus exception code 2`。
- 真实 RTU：socat 虚拟串口 + pymodbus RTU 从站，测试读取 → 接入 → 轮询 → FC06 写设定值成功；帧为 `01 06 00 08 01 2C 08 45`。
- 自定义点表：接入时提交自定义点位（温度 float32 + 压力 uint16），轮询与展示按点表驱动。
- Excel：模板下载（POI 生成）→ 上传解析 → 自定义 xlsx 导入，坏行逐行报错不阻断。
- 从站：pymodbus 主站读平台从站 Unit 1 得到设备实时值；FC06 写可写点位透传到真实设备并回读；只读点位与越界地址返回异常码；Unit 冲突在接入时拒绝。
- 配置持久化：重启后真实设备自动恢复并继续轮询。
- 单端口包：前端打进 jar，`/`、`/devices/T-01`、`/onboard`、`/assets/*`、`/api/health` 全部 200。
- 浏览器内实际点击：模拟设备 FC06 成功 6 步全绿；超时模式 FAILED，5 步完成、1 步标红。

## 边界与下一步

- Docker：compose 校验通过、三个基础镜像标签已在 Docker Hub 确认存在；加入 docker 组后执行 `./scripts/deploy-docker.sh` 即可构建镜像并启动。
- 单端口 jar 是与镜像完全等价的部署方式，已实测（`/`、SPA 路由、`/assets`、`/api` 全部 200）。
- 历史采样仍在内存，重启丢失；下一步接 H2/PostgreSQL。
- 没有登录/权限；真实生产需要加认证与操作人审计。
- 从站目前是 Modbus TCP；RTU 从站（串口对外）在下一步。
- 大字符串邮箱是平台侧模型，真实设备不要求实现；接入页的“设备编号”用于演示存储/解析。

## 本地工具链

```text
~/.local/opt/jdk21     Corretto 21
~/.local/opt/maven     Maven 3.9.9
~/.local/opt/node      Node 22
```

`env.sh` 已把这些路径加入 PATH；换机器时改这三行即可。
