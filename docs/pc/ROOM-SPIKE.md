# Room KMP 在 `:core` 的可落地性验证（spike 报告）

| 项目 | 内容 |
| --- | --- |
| 任务 | t10 —— 在 t2 搬 Room 之前，钉死「Room KMP 能否替代 app 侧 ksp Room」 |
| 结论 | **可行（fully viable）**：JVM 变体可解析、KSP 2.3.12 + Room 2.8.5 能生成实现、建库/迁移/事务/Flow 全跑通、schema 能导出；**唯一必须改代码的地方是 12 个 `Migration` 的签名与 builder 调用**，实体与 DAO 注解原样可用 |
| 验证工程 | `F:\tmp\room-kmp-spike`（仓库外，未进主工作区） |
| 主工作区影响 | 仅新增本文件；`settings.gradle.kts` / `libs.versions.toml` / 各模块 build 均未改动 |
| 日期 | 2026-11-27 |

---

## 0. 一句话给 t2

`:core` 里照抄 **§5 的配置**：`implementation("androidx.room:room-runtime:2.8.5")` +
`implementation("androidx.sqlite:sqlite-bundled:2.7.1")` + `ksp("androidx.room:room-compiler:2.8.5")`，
**不要**加 `room-ktx`；数据库用 `Room.databaseBuilder<AppDatabase>(name = 路径).setDriver(BundledSQLiteDriver())`；
12 个 `object : Migration(1, 2)`（`app/src/main/java/com/mytv/mymusic/data/AppStore.kt:1290-1689`）
只改方法签名 `migrate(db: SupportSQLiteDatabase)` → `migrate(connection: SQLiteConnection)`，
SQL 原样保留。

---

## 1. 环境

```powershell
$env:JAVA_HOME='D:\env\.jdks\temurin-25.0.3'   # JDK 25.0.3
cd F:\tmp\room-kmp-spike
.\gradlew.bat --version                          # Gradle 9.8.0
```

版本与主工程严格对齐（`gradle/libs.versions.toml`）：

| 组件 | 版本 | 主工程出处 |
| --- | --- | --- |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml:3` |
| KSP | 2.3.12 | `gradle/libs.versions.toml:4` |
| Room | 2.8.5 | `gradle/libs.versions.toml:31` |
| kotlinx-coroutines | 1.10.2 | `gradle/libs.versions.toml:29` |
| jvmTarget | 17 | `core/build.gradle.kts:15-21`、`app/build.gradle.kts:76-84` |
| Gradle | 9.8.0 | `gradle/wrapper/gradle-wrapper.properties:3` |

spike 的 `build.gradle.kts` 全文：

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("com.google.devtools.ksp") version "2.3.12"
    application
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
application { mainClass.set("com.spike.MainKt") }
ksp { arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.absolutePath) }

dependencies {
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    ksp("androidx.room:room-compiler:2.8.5")
}
```

---

## 2. 逐条实测

### 2.1 (a) `room-runtime` 的 KMP/JVM 变体能否解析出 JVM 产物 —— ✅ 可以

命令：

```powershell
.\gradlew.bat -q dependencies --configuration runtimeClasspath
```

原始输出（节选）：

```
+--- androidx.room:room-runtime:2.8.5
|    \--- androidx.room:room-runtime-jvm:2.8.5
|         +--- androidx.room:room-common:2.8.5
|         |    \--- androidx.room:room-common-jvm:2.8.5
|         +--- androidx.sqlite:sqlite:2.6.2 -> 2.7.1
|         |    \--- androidx.sqlite:sqlite-jvm:2.7.1
+--- androidx.sqlite:sqlite-bundled:2.7.1
|    \--- androidx.sqlite:sqlite-bundled-jvm:2.7.1
|         +--- androidx.sqlite:sqlite:2.7.1 (*)
\--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 (*)
```

结论：`room-runtime` 通过 Gradle module metadata 正确落到 **`room-runtime-jvm:2.8.5`**，
`sqlite` 落到 `sqlite-jvm:2.7.1`，`sqlite-bundled` 落到 `sqlite-bundled-jvm:2.7.1`（3.6 MB，内含
Windows/Linux/macOS 的 native sqlite）。**不是只有 android 变体**。

### 2.2 (b) `BundledSQLiteDriver` + `Room.databaseBuilder` 在纯 JVM 上建库 / 迁移 / 事务 —— ✅ 可以

命令：

```powershell
.\gradlew.bat clean run --console=plain
```

原始输出（`run6-run.log`，UTF-8 JVM 参数已生效）：

```
[env] java=25.0.3 db=C:\Users\1\AppData\Local\Temp\room-kmp-spike-db\spike.db
WARNING: java.lang.System::loadLibrary has been called by androidx.sqlite.driver.bundled.NativeLibraryLoader
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module

[phase1] playlists=1 entries=2
[phase2] 迁移后 playlist=歌单一 entries=2
[phase3] automix=k1 count=1 playlists=1 dirty=true
[phase4] 事务后 entries=[k9]
[done] spike ok
BUILD SUCCESSFUL in 22s
```

四步分别覆盖：

| 阶段 | 覆盖能力 | 结果 |
| --- | --- | --- |
| phase1 | v1 建库 + `@Upsert` 写歌单 + `@Transaction replaceEntries` 批量写 | playlists=1 / entries=2 |
| phase2 | 同一文件用 v2 + `MIGRATION_1_2` 打开（`ALTER TABLE ... ADD COLUMN`），旧数据保留 | 迁移后 entries 仍是 2，歌单名读回正确 |
| phase3 | 关库重开，确认落盘 + `Flow` 首值 + suspend 查询 | automix=k1 / count=1 / dirty=true |
| phase4 | `@Transaction` 默认方法真在事务里执行 | entries 被整体替换为 `[k9]` |

磁盘落点：`%TEMP%\room-kmp-spike-db\spike.db`（纯 `File` 路径，不依赖任何 Android `Context`）。

### 2.3 (c) 现有 `@Database/@Entity/@Dao/@Transaction` 注解能否原样使用 —— ✅ 原样可用

从 `data/AppStore.kt` 逐字复制的对象：

| spike 文件 | 来源 | 保留的原样特性 |
| --- | --- | --- |
| `Entities.kt: AutomixAnalysisEntity` | `AppStore.kt:964-984` | `@PrimaryKey` + 16 个 `@ColumnInfo(name=snake_case)` |
| `Entities.kt: PlaylistEntity` | `AppStore.kt:986-1003` | `defaultValue = ""` / `defaultValue = "0"`、可空列、计算属性 `bound` |
| `Entities.kt: PlaylistEntryEntity` | `AppStore.kt:1005-1023` | 复合主键 + `ForeignKey(CASCADE)` + `Index` |
| `Daos.kt: MusicDao` | `AppStore.kt:1131-1151` | `@Upsert`、`@Query`、suspend |
| `Daos.kt: LibraryDao` | `AppStore.kt:1153-1205` | `Flow<List<T>>` 返回值、`@Transaction` 默认方法、DAO 方法默认参数 `dirty: Boolean = false` |

KSP 生成物（`build/generated/ksp/main/kotlin`）：

```
LibraryDao_SpikeDbV1_Impl.kt  12668 bytes
LibraryDao_SpikeDbV2_Impl.kt  12668 bytes
MusicDao_Impl.kt               9879 bytes
SpikeDbV1_Impl.kt             12263 bytes
SpikeDbV2_Impl.kt             12644 bytes
```

schema 导出也成功：

```
schemas/com.spike.SpikeDbV1/1.json
schemas/com.spike.SpikeDbV2/2.json
```

结论：**实体与 DAO 一行不用改**。唯一需要动的是 `AppDatabase` 的 `companion object`（迁移签名）
和一个 `Room.databaseBuilder` 调用点。

### 2.4 (d) KSP 2.3.12 + Room 2.8.5 的 JVM 处理是否兼容 —— ✅ 兼容

```
> Task :kspKotlin
> Task :compileKotlin
BUILD SUCCESSFUL in 21s
```

用的是 KSP 2.x（`com.google.devtools.ksp` 2.3.12，与主工程 `libs.versions.toml:4` 同版本），
没有出现"Room 需要 KSP1"之类的报错，也不需要额外 `ksp` 目标名（纯 JVM 工程里就是 `ksp(...)`）。

---

## 3. 踩过的坑（含报错原文与解决）

### 3.1 坑①：`androidx.room:room-ktx` 是 Android-only，JVM 消费者解析不到变体

第一次构建（`run1-compile.log`）：

```
> Could not resolve androidx.room:room-ktx:2.8.5.
> No matching variant of androidx.room:room-ktx:2.8.5 was found. The consumer was configured to find a
  library for use during compile-time, compatible with Java 17, preferably in the form of class files,
  preferably optimized for standard JVMs, and its dependencies declared externally, as well as attribute
  'org.jetbrains.kotlin.platform.type' with value 'jvm' but:
      - Variant 'releaseVariantReleaseApiPublication' declares a library ... with the library elements 'aar'
      - Incompatible because this component declares a component, with the library elements 'aar' and the
        consumer needed a component, preferably in the form of class files
```

**解决**：删掉 `room-ktx`。Room 2.7+ 的 KMP 产物里 `Flow` / `suspend` 支持已经在 `room-runtime` 内，
`room-ktx` 只剩 Android 兼容壳。主工程 `app/build.gradle.kts:150` 仍留着它（Android 侧无害），
但 `:core` 不能带。

### 3.2 坑②：`AppStore.kt` 的迁移与 builder 原样复制过来编译不过

对照文件（编译一次后删除）保留的是 t2 最可能写出的代码：

```kotlin
val MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE ...") }   // Android 写法
}
Room.databaseBuilder(context, AppDatabase::class.java, "mymusic.db")                    // Android 写法
```

编译原文（`run2-compile.log`）：

```
e: ...AndroidStyleFail.kt:8:24  Unresolved reference 'db'.
e: ...AndroidStyleFail.kt:11:5  'migrate' overrides nothing. Potential signatures for overriding:
                                fun migrate(connection: SQLiteConnection): Unit
e: ...AndroidStyleFail.kt:11:30 Unresolved reference 'SupportSQLiteDatabase'.
e: ...AndroidStyleFail.kt:12:12 Unresolved reference 'execSQL'.
e: ...AndroidStyleFail.kt:16:31 Unresolved reference 'android'.
e: ...AndroidStyleFail.kt:17:61 Too many arguments for 'fun <reified T : RoomDatabase>
                                databaseBuilder(name: String, noinline factory: () -> T = ...): RoomDatabase.Builder<T>'.
```

编译器直接把正确答案写出来了：

- 迁移方法签名是 `fun migrate(connection: SQLiteConnection)`；
- builder 是 `Room.databaseBuilder<T>(name: String, factory: () -> T = ...)`，没有 `Context` 参数；
- `SupportSQLiteDatabase.execSQL` 在 JVM 侧不存在，要用 `SQLiteConnection.prepare(sql).use { it.step() }`。

**解决**（spike 里最终通过的写法）：

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.prepare("ALTER TABLE `automix_analysis` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''")
            .use { it.step() }
    }
}

Room.databaseBuilder<SpikeDbV2>(name = dbFile.absolutePath)
    .setDriver(BundledSQLiteDriver())
    .addMigrations(MIGRATION_1_2)
    .build()
```

### 3.3 坑③：迁移 DDL 的 `DEFAULT` 必须和实体的 `@ColumnInfo(defaultValue=...)` 完全一致

第一次跑迁移（`run4-run.log`）：

```
Exception in thread "main" java.lang.IllegalStateException: Migration didn't properly handle:
automix_analysis(com.spike.AutomixAnalysisEntity).
 Expected:
 TableInfo { name = 'automix_analysis', columns = { ... } }      // 没有 note 列
 Found:                              // ← 实际上有 note 列（DDL 加了），但实体没声明
        Column { name = 'note', type = 'TEXT', affinity = '2', notNull = 'true',
                 primaryKeyPosition = '0', defaultValue = '''' },
```

**解决**：实体补上 `@ColumnInfo(name = "note", defaultValue = "") val note: String = ""`（**默认值要写**），
迁移里的 `DEFAULT ''` 与它对上，校验即通过（phase2 输出 `迁移后 ... entries=2`）。

**给 t2 的提醒**：`AppStore.kt` 的 12 个迁移里凡是带 `DEFAULT` 的（例如
`AppStore.kt:995-1000` 的 `link_provider` / `link_id` / `link_cover` / `link_synced_at`），
实体侧都有对应的 `defaultValue`，搬到 KMP 侧时**两边必须一一对齐**，否则运行时才炸。

### 3.4 坑④：JDK 25 上 `sqlite-bundled` 会触发 restricted native access 警告

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::loadLibrary has been called by androidx.sqlite.driver.bundled.NativeLibraryLoader
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

不影响运行，但 t2 打包时应在 `:desktop` 的 JVM 参数里加
`--enable-native-access=ALL-UNNAMED`（与 vlcj 的 JNA 加载同类问题），否则未来 JDK 会硬性拦截。

### 3.5 坑⑤：Windows 控制台中文乱码与业务无关，但会让日志不能直接贴

默认 fork 出来的 JVM 用 GBK，输出变成 `[phase2] Ǩ�ƺ� ...`。加
`-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` 后恢复正常
（`run6-run.log` 的 `迁移后 playlist=歌单一`）。

---

## 4. 复现步骤（照抄即可）

```powershell
# 1) 建工程骨架（仓库外）
mkdir F:\tmp\room-kmp-spike\src\main\kotlin\com\spike -Force
copy F:\github\myMusic\gradlew* F:\tmp\room-kmp-spike\
copy F:\github\myMusic\gradle\wrapper\* F:\tmp\room-kmp-spike\gradle\wrapper\

# 2) 放入 §1 的 build.gradle.kts / settings.gradle.kts 与 §2.3 的实体、DAO

# 3) 编译（验证 KSP 能在 JVM 目标生成实现）
$env:JAVA_HOME='D:\env\.jdks\temurin-25.0.3'
.\gradlew.bat compileKotlin

# 4) 跑（验证建库 / 迁移 / 事务 / Flow）
.\gradlew.bat clean run

# 5) 看解析结果与生成物
.\gradlew.bat -q dependencies --configuration runtimeClasspath
dir build\generated\ksp\main\kotlin
dir schemas
```

---

## 5. t2 的施工清单（`:core` 里 Room 就这么写）

### 5.1 `core/build.gradle.kts` 追加

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)                 // 根工程已 apply false 声明 ksp
}

dependencies {
    api("androidx.room:room-runtime:2.8.5")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    ksp("androidx.room:room-compiler:2.8.5")
    // 注意：不要写 room-ktx（Android-only AAR）
}

ksp {
    // 从这一版起导出 schema，后续 AutoMigration / 迁移校验才有依据（当前 app 侧 exportSchema=false）
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.absolutePath)
}
```

> 版本建议进 `gradle/libs.versions.toml` 复用现有 `room = "2.8.5"`；新增
> `sqlite-bundled = "2.7.1"`（当前 2.8.0 只有 alpha，`sqlite-bundled` 最新稳定为 2.7.1）。

### 5.2 数据库声明（**实体与 DAO 不动**）

```kotlin
@Database(
    entities = [ /* 18 个实体原样搬：SavedSong…SubsonicTrackEntity，见 AppStore.kt:1256-1275 */ ],
    version = 13,
    exportSchema = true,                    // 现在就该打开
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao            // 8 个 DAO 全部原样
    // ...
}
```

### 5.3 12 个迁移：只改签名

`AppStore.kt:1290,1343,1359,1454,1474,1490,1499,1529,1539,1589,1637,1657`
（共 12 个 `object : Migration(`，13 处 `SupportSQLiteDatabase`）。

```kotlin
// 改前（Android）
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL(sql) }
}
// 改后（KMP）
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) { connection.prepare(sql).use { it.step() } }
}
```

SQL 文本、顺序、`CREATE TABLE IF NOT EXISTS` 都不用动。

### 5.4 打开数据库（桌面侧唯一入口）

```kotlin
fun openDatabase(dbFile: File): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)   // 注意：没有 Context
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_1_2, /* … 12 个 */)
        .build()

// Windows 落点：%APPDATA%\myMusic\mymusic.db（交给 core 的 PlatformPaths.dataDir）
```

### 5.5 测试怎么写

- 纯 JVM 单测直接 `openDatabase(File(tempDir, "test.db"))`，不需要 Robolectric；
  现有 `app/src/test/**` 里 `data/local/*Test`、`data/sync/*Test` 用到的 DAO 行为可整体迁过来跑。
- 迁移测试：像 spike 一样造 v1 库（一个 `@Database(version = 1)` 的壳 DB）再开 v13；
  或启用 `room.schemaLocation` 后走 `MigrationTestHelper`（KMP 版在 2.8 已有 JVM 产物，本 spike 未覆盖）。

### 5.6 不要做的事

1. 不要在 `:core` 引入 `androidx.room:room-ktx`（坑①）。
2. 不要把 `Room.databaseBuilder(context, X::class.java, "…")` 抄过来（坑②）。
3. 不要漏写实体的 `defaultValue`（坑③）。
4. 不要指望 `SupportSQLiteDatabase` 在任何 JVM 模块可用——它只在 android 变体里。

---

## 6. 退路评估：SQLDelight（当前**不需要**）

只有在 Room KMP 不可行时才考虑，本次实测四项全部通过，故不切换。评估留档：

| 维度 | Room KMP（选） | SQLDelight（退路） |
| --- | --- | --- |
| 现有代码迁移 | 实体/DAO 零改动，只改 12 个迁移签名 + 1 个 builder | 18 个实体改 `.sq` 定义、8 个 DAO 全重写、`Flow` 查询改 `asFlow()` |
| 迁移脚本 | 保留现有 SQL（只换签名） | `.sqm` 文件重排版本号，历史 SQL 需逐条改写成 SQLDelight 语法 |
| 程序化迁移 | 支持 `Migration` 对象 | 需要手写 `Schema.migrate` + `AfterVersion` 回调 |
| 测试面 | 现有 `app/src/test/**` 的 DAO 相关用例可复用 | 全部重写 |
| 建立成本 | 低（本 spike 已跑通） | 高（约等于重做数据层） |

结论：**Room KMP 走通，SQLDelight 不作为本期方案**；仅当未来需要在 iOS/Web 侧复用时再评估。

---

## 7. 附录：原始日志

工程与日志均在仓库外：`F:\tmp\room-kmp-spike\`

| 日志 | 对应内容 |
| --- | --- |
| `run1-compile.log` | 坑①：`room-ktx` 变体解析失败原文 |
| `run2-compile.log` | 坑②：Android 式迁移/builder 的 6 条编译错误原文 |
| `run3-compile.log` | 去掉 room-ktx 后 KSP + 编译成功 |
| `run4-run.log` | 坑③：`Migration didn't properly handle` 的 Expected/Found 全表 |
| `run5-run.log` | 修正 defaultValue 后的首次成功运行 |
| `run6-run.log` | 开启 UTF-8 + schemaLocation 后的干净全量输出（本文 §2.2 引用） |
