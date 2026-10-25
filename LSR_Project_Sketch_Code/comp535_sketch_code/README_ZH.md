# PA2 中文交接说明：本次代码修改与 edge cases

这份文档给队友阅读，说明这次在已有 PA2 上修了什么、为什么修，以及到哪里查代码。它不是 PA1 到 PA2 的全部开发记录。

## 1. 对比版本与阅读方式

- 修改前：[`cc16126`](https://github.com/DannyBoyIsNotAvailable/COMP535_PA2/commit/cc16126ce550f4562f7dfaf50f3e17b842ac4f99)。这个版本已经有 PA2 的 LSDB、Dijkstra、消息转发和统计代码，但存在运行错误和边界问题。
- 修改后：[`ae063e5`](https://github.com/DannyBoyIsNotAvailable/COMP535_PA2/commit/ae063e506a5abefc2429ae9721493ebf43450fbc)。本说明中的 Java 行号均以这个提交为准。
- [完整代码差异](https://github.com/DannyBoyIsNotAvailable/COMP535_PA2/compare/cc16126ce550f4562f7dfaf50f3e17b842ac4f99...ae063e506a5abefc2429ae9721493ebf43450fbc)。

三个主要文件都在 `src/main/java/socs/network/node/`。VSCode 打开文件后，按 `Ctrl+G` 输入行号即可定位；全项目搜索 `EDGE CASE` 可以查到本次相关注释，共 21 处。

旧的 `src/main/java/socs/network/node/README.md` 是 PA1 的历史说明，其中“尚未实现 LSDB 同步”等描述不适用于当前 PA2；阅读当前实现时以 Java 源码及本说明为准。

## 2. 文件和代码行数

这里统计的是物理行数，包含注释和空行，不等于可执行代码行数。新增、删除按 GitHub 的版本差异统计；改写一行通常同时计入一次删除和一次新增。本说明文件不计入下表。

| 文件 | 修改前总行数 | 修改后总行数 | 新增行 | 删除行 | 净变化 |
|---|---:|---:|---:|---:|---:|
| `Router.java` | 1001 | 1052 | 128 | 77 | +51 |
| `LinkStateDatabase.java` | 294 | 313 | 39 | 20 | +19 |
| `MessageCount.java` | 47 | 49 | 3 | 1 | +2 |
| **合计** | **1342** | **1414** | **170** | **98** | **+72** |

本次没有新增或删除 Java 文件，没有删除整个方法。新增的是 `openSocket()` 和 `validLSA()` 两个方法。

`Path.java`、`RouterDescription.java`、`Link.java`、消息类和 `conf/*.conf` 未在这轮修改中更改。`Path.java` 原来就存在，并非本次新建。

## 3. Router.java：新增与修改的方法

[查看对应版本的 Router.java](https://github.com/DannyBoyIsNotAvailable/COMP535_PA2/blob/ae063e506a5abefc2429ae9721493ebf43450fbc/LSR_Project_Sketch_Code/comp535_sketch_code/src/main/java/socs/network/node/Router.java)

下表的行数指从方法声明到结束大括号，包含方法内部的注释和空行，不含声明之前的说明注释。

| 方法 | 当前行号 | 方法行数 | 本次变化与原因 |
|---|---|---:|---|
| `openSocket()` | 272–284 | 13 | **新增**。统一创建 socket，设置 5000 ms 连接超时和读取超时，失败时关闭 socket；读取超时在构造输入流之前生效。 |
| `readPacket()` | 287–295 | 9 | 收到非 `SOSPFPacket` 对象时仍抛出 `IOException`；收到 `null` 时不再对它调用 `getClass()`。 |
| `isTwoWay()` | 299–305 | 7 | 只检查该 Link 的 `router2.status`，并用 `portLock` 保护读取；允许传入空 Link 并返回 false。 |
| `addNeighborToLSA()` | 318–328 | 11 | 用局部变量保存 `getPort()` 结果，将 `> 0` 改为 `>= 0`，并把状态检查、端口检查和插入操作放在同一个 `portLock` 内。 |
| `sendOneWayMessage()` | 335–349 | 15 | 改用 `openSocket()`；输入流纳入自动关闭。等待的是对端对象流的 header，不是应用层回复；发送并 flush 后增加对应类型的 sent 计数。 |
| `broadcastLSAUpdate()` | 356–367 | 12 | 改为 `neighbor.simulatedIPAddress.equals(exception)`，允许本地产生广播时传入 `null`；仍只发送给 TWO_WAY 邻居，并排除来源邻居。 |
| `nextHopLink()` | 388–400 | 13 | 先检查 `path == null`，再调用 `nextHop()`；替换原来重复检查两次 `path.nextHop()` 的条件。 |
| `connectionHandler()` | 431–460 | 30 | 在构造 `ObjectInputStream` 之前设置读取超时；原来的按包类型分发和 received 计数继续保留。 |
| `processAttach()` | 474–553 | 80 | 发送前拒绝负权重和非正进程端口；使用统一 socket；收到回复后检查包类型和双方模拟 IP，按真实类型计数。等待人工 Y/N 时将读取超时设为 0。 |
| `handleAttachRequest()` | 561–617 | 57 | 接收端也拒绝负权重，避免绕过本机命令校验；Y/N 提示改由 `askUserYesNo()` 在发布待答状态之后输出。 |
| `askUserYesNo()` | 623–637 | 15 | 先设置 `pendingAttachDecision`，再打印问题，避免快速输入的 Y/N 被当作普通命令。 |
| `processStart()` | 662–679 | 18 | 每次完成邻居握手后都广播完整 LSDB，不再仅在本机 LSA 改变时发送；重复 start 也可重新交换已有信息。 |
| `helloHandshake()` | 687–734 | 48 | 使用统一 socket；检查第二个 HELLO 的类型和双方模拟 IP；第三个 HELLO 写出并 flush 后再推进本地邻居状态；之后总是尝试幂等插入邻居。 |
| `handleHello()` | 741–776 | 36 | 检查首包目的模拟 IP，以及第三个 HELLO 的类型、来源和目的模拟 IP；推进状态后总是尝试插入邻居。新邻居触发广播；原有邻居也会收到完整 LSDB。 |
| `processDetect()` | 822–828 | 7 | 路径不存在时将原来的 `NO path` 改为 `Path not found`。 |
| `processSend()` | 890–907 | 18 | 无路径时安全返回；发送给自身时直接本地交付，输出发送和接收提示，不使用网络 socket。 |
| `handleApplicationMessage()` | 924–942 | 19 | 先确认下一跳存在，再打印转发提示；直接转发原包，保留最初的来源 IP、目的 IP 和正文；不在中间路由器打印正文。 |

### 为什么 isTwoWay 不检查 router1？

`link.router1` 引用的是共享的本机 `rd`；当前 HELLO 逻辑更新的是每条 Link 上的邻居 `link.router2.status`。本机 `rd.status` 没有随着这条邻接关系更新。因此同时要求两者为 TWO_WAY，会把已经握手成功的邻居也排除掉。这里只检查 `router2` 是配合现有状态设计，而不是省略一次双向握手。

### 为什么缓存 getPort 的结果，并使用 >= 0？

`getPort()` 返回本路由器 `ports[]` 的槽位下标，合法值是 0–3，找不到返回 -1。它不是 TCP 的 Process Port。原来的 `> 0` 会漏掉第一个槽位；局部变量让本次检查和写入 LSA 使用同一个槽位结果，也避免重复查找。外层 `portLock` 使检查和插入成为同一段临界区。

### 为什么重复 start 仍要发送完整 LSDB？

本机 LSA 没改变，不代表邻居已经拥有本机知道的所有其他 LSA。重复握手和两个已有网络相连时仍交换完整快照，邻居才有机会补充缺失的信息。接收端只接受未知来源或更大序列号，所以接收相同快照不会无休止转发，也不会因此增加本机 LSA 序列号。

## 4. LinkStateDatabase.java：新增与修改的方法

[查看对应版本的 LinkStateDatabase.java](https://github.com/DannyBoyIsNotAvailable/COMP535_PA2/blob/ae063e506a5abefc2429ae9721493ebf43450fbc/LSR_Project_Sketch_Code/comp535_sketch_code/src/main/java/socs/network/node/LinkStateDatabase.java)

| 方法或结构 | 当前行号 | 方法行数 | 本次变化与原因 |
|---|---|---:|---|
| `PQEntry` / 构造器 | 42–49 / 45–48 | 构造器 4 | `distance` 字段和构造参数从 `int` 改为 `long`。 |
| `findShortestPathTo()` | 77–137 | 61 | 空目的地址返回 null；距离表用 `Long`，优先队列用 `Long.compare`，避免合法权重相加后溢出成负数。Dijkstra 和路径重建原本就存在。 |
| `getShortestPath()` | 165–173 | 9 | 仅删除已过时的 TODO 注释；路径格式化逻辑没有重写。 |
| `toString()` | 193–204 | 12 | 新增 `synchronized` 和 `@Override`，防止打印 LSDB 时另一个线程正在修改集合。 |
| `addLinkToLSA()` | 213–231 | 19 | 拒绝 null 邻居 IP、负槽位或负权重；保留原有重复邻居检查和“真正新增时序列号加一”的行为。 |
| `merge()` | 243–261 | 19 | 空列表直接返回空结果；过滤无效 LSA 和本机 LSA；只接受未知或较新 LSA；存入数据库及返回转发结果时分别做深拷贝。 |
| `validLSA()` | 263–271 | 9 | **新增**。检查 LSA、来源 ID、links 集合及每条边，拒绝 null 对象、null linkID 和负权重；一条边非法就拒绝整份 LSA。 |
| `deepCopy()` | 284–290 | 7 | 方法实现不变。更正方法前的说明，明确实际选择的是“锁内复制、锁外发送”，不是长时间持锁发送。 |
| `copy()` | 293–306 | 14 | 从 `public` 改为 `private`，作为类内部的深拷贝工具；复制 LSA 和每条 LinkDescription 的逻辑保留。 |

`findShortestPathTo()`、`addLinkToLSA()`、`merge()`、`deepCopy()` 原本就有 `synchronized`，不是这次才全部加锁。这次新增同步修饰的是 `toString()`；同时调整了 Router 内邻居状态和槽位检查的锁范围。

### 为什么 merge 要做两份深拷贝？

收到的 LSA、数据库保存的 LSA、准备转发的 LSA 不应共享可变的 `links` 集合和 LinkDescription。否则更改其中一份可能连带修改数据库，或者让序列化时读到一个正在变化的对象。现在存储副本和转发副本分别独立；发送完整数据库时也通过 `deepCopy()` 获取快照。

### 为什么忽略收到的本机 LSA？

当前协议由每个路由器自己维护自己的 LSA。邻居可能沿环路把本机曾经发送的 LSA 带回来，不能让它覆盖本机正在维护的内容。这个处理不包含进程重启后的序列号恢复协议，不能将它当作完整的重启恢复机制。

## 5. MessageCount.java：字段修复，计数方法保留

[查看对应版本的 MessageCount.java](https://github.com/DannyBoyIsNotAvailable/COMP535_PA2/blob/ae063e506a5abefc2429ae9721493ebf43450fbc/LSR_Project_Sketch_Code/comp535_sketch_code/src/main/java/socs/network/node/MessageCount.java)

| 位置 | 当前行号 | 说明 |
|---|---|---|
| `count_obj` | 11–13 | 从未初始化的 `Object` 改为 `private final Object count_obj = new Object();`，并补充 edge case 注释。 |
| `countReceived()` | 19–25，7 行 | 方法原样保留；在同一个锁内增加对应 received 计数。 |
| `countSent()` | 27–34，8 行 | 方法原样保留；在同一个锁内增加对应 sent 计数。 |
| `reportMessageCount()` | 36–47，12 行 | 方法原样保留；持锁读取各类型的 sent/received。 |

原来调用 `synchronized(count_obj)` 时，`count_obj` 仍为 null，所以第一次收包、发包或执行 stats 就可能抛出 NullPointerException。这里要初始化的是锁对象；`int[]` 中的计数本来就由 Java 自动初始化为 0。

包类型仍为 HELLO=0、LSAUPDATE=1、APP_MESSAGE=2、ATTACH=3。广播给 N 个邻居计 N 次发送；中间路由器转发一条应用消息，接收和发送各增加 1。发送给自身的本地交付不计作网络收发。

## 6. 删除了哪些内容？

这次没有删除整个方法，也没有删除配置文件。删除或替换的是以下局部代码：

| 原有内容 | 处理方式 | 相关当前位置 |
|---|---|---|
| `isTwoWay()` 同时要求 `router1` 和 `router2` 为 TWO_WAY | 替换为检查该邻居的 `router2.status` | Router 299–305 |
| `getPort(link) > 0` 和重复调用 getPort | 缓存槽位并使用 `port >= 0` | Router 318–328 |
| `exception.equals(...)` | 改为从非空的邻居模拟 IP 调用 equals | Router 356–367 |
| 对 `path.nextHop()` 重复检查两次 | 改为先判断 path 是否为空 | Router 388–400 |
| `lsaChanged` 标记及“只有本机 LSA 改变才广播”的限制 | 每次 start 后交换完整 LSDB | Router 662–679 |
| 只在 `advanceNeighborStatus()` 返回 true 时才插入邻居 | 状态推进后总是尝试幂等插入 | Router 687–776 |
| 单向发送中的普通局部输入流 `input` | 改为自动关闭的 `peerInput` 资源 | Router 335–349 |
| 转发时新建 `nextPacket` 并重新拼装正文 | 直接发送原 packet | Router 924–942 |
| 原来中间节点的 `Next Hop:` 输出 | 当前转发分支只保留来源、目的的提示；此项属于输出变化 | Router 938 |
| 未使用的 `java.util.Queue` 导入、已完成的 TODO、过时注释 | 删除导入和 TODO，修正说明 | LinkStateDatabase 6–14、165–173、273–282 |

## 7. Edge cases：具体场景、处理与位置

以下区分“代码已作处理”和“已完成真实网络验证”：代码包含这些处理，并不等于每一项都已在真实网络中穷尽测试。

| 场景 | 当前处理 | 代码位置 |
|---|---|---|
| 邻居放在第 0 个槽位 | 0 是合法下标；只有 -1 表示找不到 | Router 318–328 |
| 本机 `rd.status` 没更新 | 按每条 Link 的邻居状态判断 TWO_WAY | Router 299–305 |
| 本机主动广播，没有来源邻居 | exception 可以为 null，不触发空指针 | Router 356–367 |
| detect/send 的目的地址不可达 | 查路径返回 null；Router 安全输出 `Path not found` | Router 388–400、822–828、890–907 |
| 目的地址就是自身 | detect 得到只有自身的零边路径；send 直接本地交付 | LSDB 77–137；Router 890–907 |
| 路径存在，但下一跳未 TWO_WAY 或已不存在 | 不通过该 Link 转发 | Router 388–400 |
| 重复运行 start | 重新交换数据库，但不重复添加邻居，不无故增加本机 LSA 序列号 | Router 662–776；LSDB 213–231 |
| 两端几乎同时 start | 状态单向推进；插入邻居不再依赖哪个线程先改变状态，重复插入安全返回 false | Router 228–241、687–776 |
| 后加入路由器或连接两个已有网络 | HELLO 后发送完整 LSDB 快照；接收端转发真正接受的更新 | Router 374–379、662–776 |
| 环形拓扑中的重复 LSA | 相同或更小序列号不再接收；只转发新接受的 LSA，并排除来源邻居 | LSDB 243–261；Router 356–379 |
| 收到本机 LSA、空更新列表或非法 LSA | 忽略本机 LSA；空列表无更新；非法 LSA 整份拒绝 | LSDB 243–271 |
| 一个 LSA 带有负权重或空边 | 不把部分内容安装到数据库，整份拒绝 | LSDB 263–271 |
| 用户 attach 传入负权重 | 本机及对端都检查；0 权重合法 | Router 474–480、577–579 |
| 多条大权重边相加 | 距离改用 long，避免 int 溢出 | LSDB 42–48、77–137 |
| 终端打印 LSDB 的同时收到更新 | toString、merge、deepCopy、路径查询使用同一个 LSDB 对象监视器 | LSDB 77、193、243、284 |
| 更新对象和发送快照意外共享内容 | 存储、转发与完整快照分别深拷贝 | LSDB 243–261、284–306 |
| TCP 已连接但对方不发送对象流 header 或 HELLO | 建立输入流前设置读取超时；连接本身也有 5000 ms 超时 | Router 272–284、431–460 |
| 对端用户花较长时间决定 Y/N | 连接与 header 阶段仍限时；发送 attach 后的人工答复阶段允许等待 | Router 505–521 |
| 用户看到提示后马上输入 Y/N | 先发布待答 future，再打印问题 | Router 623–637 |
| 握手收到错误类型、来源或目的的包 | 检查实际包字段并拒绝错误回复，计数按实际收到的类型记录 | Router 521–527、703–709、758–764 |
| 收到序列化的 null 对象 | 抛 IOException，而不是在错误信息中再次抛空指针 | Router 287–295 |
| 多个线程同时更新 stats | 共享已初始化的 final 锁，避免空指针和丢失增量 | MessageCount 11–47 |
| 消息经过多个中间节点 | 直接转发原包，保留原始来源与正文，中间节点不打印正文 | Router 924–942 |

### 并发处理的边界

`portLock` 管槽位和邻居状态；LSDB 的 `synchronized` 管数据库；`count_obj` 管计数。`addNeighborToLSA()` 可以在持有 portLock 时调用同步的 LSDB 方法；当前 LSDB 方法不会反向获取 portLock。完整数据库先在锁内复制，再释放锁进行网络发送，不把慢速网络 I/O 放进这两个锁的临界区。

## 8. 去哪里查整体流程？

| 想了解的功能 | 建议阅读顺序 |
|---|---|
| start 如何建立邻居并同步 | Router `processStart()` 662 → `helloHandshake()` 687 / `handleHello()` 741 → `addNeighborToLSA()` 318 → LSDB `addLinkToLSA()` 213 → Router `broadcastLSAUpdate()` 356 |
| 收到 LSA 后怎么判断和转发 | Router `connectionHandler()` 431 → `receiveLSAUpdate()` 374 → LSDB `merge()` 243 / `validLSA()` 263 → Router `broadcastLSAUpdate()` 356 |
| detect 如何查最短路径 | Router `processDetect()` 822 → LSDB `getShortestPath()` 165 → `findShortestPathTo()` 77 → `constructGraph()` 58 |
| send 如何选下一跳 | Router `processSend()` 890 / `handleApplicationMessage()` 924 → `nextHopLink()` 388 → LSDB `findShortestPathTo()` 77 → Path `nextHop()` 20 |
| stats 在哪里累加和打印 | Router `sendOneWayMessage()` 335、各 HELLO/attach 发送处和接收处 → MessageCount `countSent()` 27 / `countReceived()` 19；Router `runCommand()` 996 分发 stats → `reportMessageCount()` 36 |

`getPort()`（Router 307–316）、`receiveLSAUpdate()`（Router 374–379）、`constructGraph()`（LSDB 58–70）和 `Path.nextHop()`（Path 20–25）的实现这次没有重写，列出它们是为了方便沿调用关系查代码。

## 9. 地址和运行方式

在 VSCode 打开包含 `pom.xml` 的 `comp535_sketch_code` 目录，在终端编译：

```text
mvn clean package assembly:single
```

每个路由器使用独立终端；五个终端分别运行一条：

```text
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router2.conf
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router3.conf
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router4.conf
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router5.conf
```

拉取源码更新后必须重新编译。仓库原来就带有 `out/`、`target/` 等生成物；`ae063e5` 这次只更新了三个 Java 源文件，不能假定仓库里已有的旧 JAR/class 与新源码一致。

| 标识 | 实际用途 | 使用规则 |
|---|---|---|
| Process IP | 实际 socket 通信地址 | 使用目标终端启动时显示的值；源码通过 `InetAddress.getLocalHost().getHostAddress()` 获取，没有硬编码回环地址 |
| Process Port | TCP 监听端口 | 使用目标启动时显示的数字，重启可能变化 |
| Simulated IP | 拓扑、LSA、detect/send 的节点标识 | 从各自配置文件读取，五个节点分别不同 |
| 本地槽位 0–3 | 当前路由器的 Link 在 ports[] 中的位置 | 与 TCP Process Port 是两回事 |

当前前五份配置中的模拟 IP 为：

| 配置 | Simulated IP |
|---|---|
| router1.conf | 192.168.1.1 |
| router2.conf | 192.168.1.100 |
| router3.conf | 192.168.2.1 |
| router4.conf | 192.168.3.1 |
| router5.conf | 192.168.4.1 |

命令格式中的方括号表示需要替换的参数，不能原样输入；特别是 Process Port 必须填数字。

```text
attach [目标Process IP] [目标Process Port] [目标Simulated IP] [非负权重]
```

在对端看到询问后回答 Y 或 N；发送端确认接受后再 start。attach 只安装 Link，不同步 LSDB。后续 `detect`、`send` 使用的是模拟 IP，例如在 R1 输入：

```text
detect 192.168.4.1
send 192.168.4.1 Hi Router 5
stats
lsd
```

## 10. 验证记录与仍需注意的范围

- 修改后完成 Maven 编译、打包，以及 20 项 LSDB/计数检查和 14 项路由器内部/并发检查。检查包括第 0 槽位、未知/自身目的地址、旧/新序列号、深拷贝、负权重、long 距离、并发计数和并发 LSDB 访问。
- 上述检查使用临时验证程序，未作为测试文件提交到仓库。自动化环境尝试多进程 socket 联调时，局域网和回环连接均被运行环境拒绝，因此不能据此声称已完成全部真实网络测试。
- 本机人工测试后，使用者反馈“基本可以了”；这不是对每个 edge case 都已通过的逐项确认。队友应继续用五个路由器重复测试 start、detect、send、stats，以及环路、较贵的直连边和后加入节点。
- `processDisconnect()`（Router 836–838）、`processConnect()`（847–850）、`updateWeight()`（855–858）、`processUpdate()`（868–870）仍是原有空方法。这轮没有实现 PA3 的动态拓扑功能，也没有补充后台故障检测、应用消息 TTL 或进程重启后的序列号恢复。对端退出后，不应把本轮修复理解为已完成全网撤销失效链路。
- 本说明记录当前代码，没有调整终端输出。对照 PA2 PDF 的示例，当前 detect 成功时未加 `Path found:`；中间节点打印 `Forwarding packet from ...` 且没有 `Next hop:`；目的节点正文前未加 `Message:`。PDF 要求输出尽量贴近示例，提交前需单独核对这些格式差异。
- 这份交接说明不代替组队 collaboration statement。PA2 要求写明真实分工；应由两位成员确认各自实际贡献后补入提交用 README，不能凭此文档推断分工。
